/**
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar.phone;

import static android.view.Display.INVALID_DISPLAY;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.DeviceConfig;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.ISystemGestureExclusionListener;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.policy.GestureNavigationSettingsObserver;
import com.android.internal.util.pixeldust.LineageButtons;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.NavigationEdgeBackPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.tracing.ProtoTraceable;
import com.android.systemui.tracing.ProtoTracer;
import com.android.systemui.tracing.nano.EdgeBackGestureHandlerProto;
import com.android.systemui.tracing.nano.SystemUiTraceProto;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Utility class to handle edge swipes for back gesture
 */
public class EdgeBackGestureHandler extends CurrentUserTracker implements DisplayListener,
        PluginListener<NavigationEdgeBackPlugin>, ProtoTraceable<SystemUiTraceProto> {

    private static final String TAG = "EdgeBackGestureHandler";
    private static final int MAX_LONG_PRESS_TIMEOUT = SystemProperties.getInt(
            "gestures.back_timeout", 250);

    private ISystemGestureExclusionListener mGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId,
                        Region systemGestureExclusion, Region unrestrictedOrNull) {
                    if (displayId == mDisplayId) {
                        mMainExecutor.execute(() -> {
                            mExcludeRegion.set(systemGestureExclusion);
                            mUnrestrictedExcludeRegion.set(unrestrictedOrNull != null
                                    ? unrestrictedOrNull : systemGestureExclusion);
                        });
                    }
                }
            };

    private OverviewProxyService.OverviewProxyListener mQuickSwitchListener =
            new OverviewProxyService.OverviewProxyListener() {
                @Override
                public void onQuickSwitchToNewTask(@Surface.Rotation int rotation) {
                    mStartingQuickstepRotation = rotation;
                    updateDisabledForQuickstep();
                }
            };

    private TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            mGestureBlockingActivityRunning = isGestureBlockingActivityRunning();
        }
    };

    private final Context mContext;
    private final OverviewProxyService mOverviewProxyService;
    private final Runnable mStateChangeCallback;

    private final PluginManager mPluginManager;
    // Activities which should not trigger Back gesture.
    private final List<ComponentName> mGestureBlockingActivities = new ArrayList<>();

    private final Point mDisplaySize = new Point();
    private final int mDisplayId;

    private final Executor mMainExecutor;

    private final Region mExcludeRegion = new Region();
    private final Region mUnrestrictedExcludeRegion = new Region();

    // The left side edge width where touch down is allowed
    private int mEdgeWidthLeft;
    // The right side edge width where touch down is allowed
    private int mEdgeWidthRight;
    // The bottom gesture area height
    private float mBottomGestureHeight;
    // Displaysize divider to check the edge height where touch down is allowed
    private int mYDeadzoneDivider = 0;
    // The slop to distinguish between horizontal and vertical motion
    private float mTouchSlop;
    // Duration after which we consider the event as longpress.
    private final int mLongPressTimeout;
    private int mStartingQuickstepRotation = -1;
    // We temporarily disable back gesture when user is quickswitching
    // between apps of different orientations
    private boolean mDisabledForQuickstep;

    private final PointF mDownPoint = new PointF();
    private final PointF mEndPoint = new PointF();
    private boolean mThresholdCrossed = false;
    private boolean mAllowGesture = false;
    private boolean mLogGesture = false;
    private boolean mInRejectedExclusion = false;
    private boolean mIsOnLeftEdge;

    private int mTimeout = 2000; //ms
    private int mLeftLongSwipeAction;
    private int mRightLongSwipeAction;
    private boolean mIsExtendedSwipe;
    private int mLeftVerticalSwipeAction;
    private int mRightVerticalSwipeAction;
    private boolean mBlockNextEvent;
    private Handler mHandler;
    private final Vibrator mVibrator;
    private boolean mImeVisible;

    private boolean mIsAttached;
    private boolean mIsGesturalModeEnabled;
    private boolean mIsEnabled;
    private boolean mIsNavBarShownTransiently;
    private boolean mIsBackGestureAllowed;
    private boolean mGestureBlockingActivityRunning;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;

    private NavigationEdgeBackPlugin mEdgeBackPlugin;
    private int mLeftInset;
    private int mRightInset;
    private int mSysUiFlags;

    private final GestureNavigationSettingsObserver mGestureNavigationSettingsObserver;

    private final NavigationEdgeBackPlugin.BackCallback mBackCallback =
            new NavigationEdgeBackPlugin.BackCallback() {
                @Override
                public void triggerBack() {
                    sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
                    sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);

                    mOverviewProxyService.notifyBackAction(true, (int) mDownPoint.x,
                            (int) mDownPoint.y, false /* isButton */, !mIsOnLeftEdge);
                    logGesture(mInRejectedExclusion
                            ? SysUiStatsLog.BACK_GESTURE__TYPE__COMPLETED_REJECTED
                            : SysUiStatsLog.BACK_GESTURE__TYPE__COMPLETED);
                }

                @Override
                public void cancelBack() {
                    // called by MotionEvent.ACTION_CANCEL and MotionEvent.ACTION_UP from EdgePanel callback
                    logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE);
                    mOverviewProxyService.notifyBackAction(false, (int) mDownPoint.x,
                            (int) mDownPoint.y, false /* isButton */, !mIsOnLeftEdge);
                }
            };

    public EdgeBackGestureHandler(Context context, OverviewProxyService overviewProxyService,
            SysUiState sysUiFlagContainer, PluginManager pluginManager,
            Runnable stateChangeCallback) {
        super(Dependency.get(BroadcastDispatcher.class));
        mContext = context;
        mDisplayId = context.getDisplayId();
        mMainExecutor = context.getMainExecutor();
        mOverviewProxyService = overviewProxyService;
        mPluginManager = pluginManager;
        mStateChangeCallback = stateChangeCallback;
        ComponentName recentsComponentName = ComponentName.unflattenFromString(
                context.getString(com.android.internal.R.string.config_recentsComponentName));
        if (recentsComponentName != null) {
            String recentsPackageName = recentsComponentName.getPackageName();
            PackageManager manager = context.getPackageManager();
            try {
                Resources resources = manager.getResourcesForApplication(recentsPackageName);
                int resId = resources.getIdentifier(
                        "gesture_blocking_activities", "array", recentsPackageName);

                if (resId == 0) {
                    Log.e(TAG, "No resource found for gesture-blocking activities");
                } else {
                    String[] gestureBlockingActivities = resources.getStringArray(resId);
                    for (String gestureBlockingActivity : gestureBlockingActivities) {
                        mGestureBlockingActivities.add(
                                ComponentName.unflattenFromString(gestureBlockingActivity));
                    }
                }
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Failed to add gesture blocking activities", e);
            }
        }

        Dependency.get(ProtoTracer.class).add(this);
        mLongPressTimeout = Math.min(MAX_LONG_PRESS_TIMEOUT,
                ViewConfiguration.getLongPressTimeout());

        mGestureNavigationSettingsObserver = new GestureNavigationSettingsObserver(
                mContext.getMainThreadHandler(), mContext, this::onNavigationSettingsChanged);

        updateCurrentUserResources();
        sysUiFlagContainer.addCallback(sysUiFlags -> mSysUiFlags = sysUiFlags);

        mHandler = new Handler();
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void updateCurrentUserResources() {
        Resources res = Dependency.get(NavigationModeController.class).getCurrentUserContext()
                .getResources();
        mEdgeWidthLeft = mGestureNavigationSettingsObserver.getLeftSensitivity(res);
        mEdgeWidthRight = mGestureNavigationSettingsObserver.getRightSensitivity(res);
        mIsBackGestureAllowed =
                !mGestureNavigationSettingsObserver.areNavigationButtonForcedVisible();

        mYDeadzoneDivider = mGestureNavigationSettingsObserver.getDeadZoneMode();

        mTimeout = mGestureNavigationSettingsObserver.getLongSwipeTimeOut();
        mLeftLongSwipeAction = mGestureNavigationSettingsObserver.getLeftLongSwipeAction();
        mRightLongSwipeAction = mGestureNavigationSettingsObserver.getRightLongSwipeAction();
        mIsExtendedSwipe = mGestureNavigationSettingsObserver.getIsExtendedSwipe();
        mLeftVerticalSwipeAction = mGestureNavigationSettingsObserver.getLeftLSwipeAction();
        mRightVerticalSwipeAction = mGestureNavigationSettingsObserver.getRightLSwipeAction();

        final DisplayMetrics dm = res.getDisplayMetrics();
        final float defaultGestureHeight = res.getDimension(
                com.android.internal.R.dimen.navigation_bar_gesture_height) / dm.density;
        final float gestureHeight = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.BACK_GESTURE_BOTTOM_HEIGHT,
                defaultGestureHeight);
        mBottomGestureHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, gestureHeight,
                dm);

        // Reduce the default touch slop to ensure that we can intercept the gesture
        // before the app starts to react to it.
        // TODO(b/130352502) Tune this value and extract into a constant
        final float backGestureSlop = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.BACK_GESTURE_SLOP_MULTIPLIER, 0.75f);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop() * backGestureSlop;
    }

    private void onNavigationSettingsChanged() {
        boolean wasBackAllowed = isHandlingGestures();
        updateCurrentUserResources();
        if (wasBackAllowed != isHandlingGestures()) {
            mStateChangeCallback.run();
        }
    }

    @Override
    public void onUserSwitched(int newUserId) {
        updateIsEnabled();
        updateCurrentUserResources();
    }

    /**
     * @see NavigationBarView#onAttachedToWindow()
     */
    public void onNavBarAttached() {
        mIsAttached = true;
        mOverviewProxyService.addCallback(mQuickSwitchListener);
        updateIsEnabled();
        startTracking();
    }

    /**
     * @see NavigationBarView#onDetachedFromWindow()
     */
    public void onNavBarDetached() {
        mIsAttached = false;
        mOverviewProxyService.removeCallback(mQuickSwitchListener);
        updateIsEnabled();
        stopTracking();
    }

    /**
     * @see NavigationModeController.ModeChangedListener#onNavigationModeChanged
     */
    public void onNavigationModeChanged(int mode) {
        mIsGesturalModeEnabled = QuickStepContract.isGesturalMode(mode);
        updateIsEnabled();
        updateCurrentUserResources();
    }

    public void onNavBarTransientStateChanged(boolean isTransient) {
        mIsNavBarShownTransiently = isTransient;
    }

    private void disposeInputChannel() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private void updateIsEnabled() {
        boolean isEnabled = mIsAttached && mIsGesturalModeEnabled;
        if (isEnabled == mIsEnabled) {
            return;
        }
        mIsEnabled = isEnabled;
        disposeInputChannel();

        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.onDestroy();
            mEdgeBackPlugin = null;
        }

        if (!mIsEnabled) {
            mGestureNavigationSettingsObserver.unregister();
            mContext.getSystemService(DisplayManager.class).unregisterDisplayListener(this);
            mPluginManager.removePluginListener(this);
            ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mTaskStackListener);

            try {
                WindowManagerGlobal.getWindowManagerService()
                        .unregisterSystemGestureExclusionListener(
                                mGestureExclusionListener, mDisplayId);
            } catch (RemoteException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to unregister window manager callbacks", e);
            }

        } else {
            mGestureNavigationSettingsObserver.register();
            updateDisplaySize();
            mContext.getSystemService(DisplayManager.class).registerDisplayListener(this,
                    mContext.getMainThreadHandler());
            ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);

            try {
                WindowManagerGlobal.getWindowManagerService()
                        .registerSystemGestureExclusionListener(
                                mGestureExclusionListener, mDisplayId);
            } catch (RemoteException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to register window manager callbacks", e);
            }

            // Register input event receiver
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "edge-swipe", mDisplayId);
            mInputEventReceiver = new SysUiInputEventReceiver(
                    mInputMonitor.getInputChannel(), Looper.getMainLooper());

            // Add a nav bar panel window
            setEdgeBackPlugin(new NavigationBarEdgePanel(mContext));
            mPluginManager.addPluginListener(
                    this, NavigationEdgeBackPlugin.class, /*allowMultiple=*/ false);
        }
    }

    @Override
    public void onPluginConnected(NavigationEdgeBackPlugin plugin, Context context) {
        setEdgeBackPlugin(plugin);
    }

    @Override
    public void onPluginDisconnected(NavigationEdgeBackPlugin plugin) {
        setEdgeBackPlugin(new NavigationBarEdgePanel(mContext));
    }

    private void setEdgeBackPlugin(NavigationEdgeBackPlugin edgeBackPlugin) {
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.onDestroy();
        }
        mEdgeBackPlugin = edgeBackPlugin;
        mEdgeBackPlugin.setBackCallback(mBackCallback);
        mEdgeBackPlugin.setLayoutParams(createLayoutParams());
        updateDisplaySize();
    }

    public boolean isHandlingGestures() {
        return mIsEnabled && mIsBackGestureAllowed;
    }

    private WindowManager.LayoutParams createLayoutParams() {
        Resources resources = mContext.getResources();
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_width),
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_height),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.privateFlags |=
                WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        layoutParams.setTitle(TAG + mContext.getDisplayId());
        layoutParams.accessibilityTitle = mContext.getString(R.string.nav_bar_edge_panel);
        layoutParams.windowAnimations = 0;
        layoutParams.setFitInsetsTypes(0 /* types */);
        return layoutParams;
    }

    private void onInputEvent(InputEvent ev) {
        if (ev instanceof MotionEvent) {
            onMotionEvent((MotionEvent) ev);
        }
    }

    private boolean isWithinTouchRegion(int x, int y) {
        // Disallow if we are in the bottom gesture area
        if (y >= (mDisplaySize.y - mBottomGestureHeight)) {
            return false;
        }

        // If the point is way too far (twice the margin), it is
        // not interesting to us for logging purposes, nor we
        // should process it.  Simply return false and keep
        // mLogGesture = false.
        if (x > 2 * (mEdgeWidthLeft + mLeftInset)
                && x < (mDisplaySize.x - 2 * (mEdgeWidthRight + mRightInset))) {
            return false;
        }

        if (mYDeadzoneDivider != 0 && y < (mDisplaySize.y / mYDeadzoneDivider)) {
            return false;
        }

        // Denotes whether we should proceed with the gesture.
        // Even if it is false, we may want to log it assuming
        // it is not invalid due to exclusion.
        boolean withinRange = x <= mEdgeWidthLeft + mLeftInset
                || x >= (mDisplaySize.x - mEdgeWidthRight - mRightInset);

        // Always allow if the user is in a transient sticky immersive state
        if (mIsNavBarShownTransiently) {
            mLogGesture = true;
            return withinRange;
        }

        /* If Launcher is showing and wants to block back gesture, let's still trigger our custom
        swipe actions at the very bottom of the screen, because we are cool.*/
        boolean isInExcludedRegion = false;
        // still block extended swipe if keyboard is showing, to avoid conflicts with IME gestures
        if (!mImeVisible && (
                mIsExtendedSwipe
                || (mLeftLongSwipeAction != 0 && mIsOnLeftEdge)  || (mRightLongSwipeAction != 0 && !mIsOnLeftEdge))) {
            isInExcludedRegion= mExcludeRegion.contains(x, y)
                && y < ((mDisplaySize.y / 4) * 3);
        } else {
            isInExcludedRegion= mExcludeRegion.contains(x, y);
        }
        if (isInExcludedRegion) {
            if (withinRange) {
                // Log as exclusion only if it is in acceptable range in the first place.
                mOverviewProxyService.notifyBackAction(
                        false /* completed */, -1, -1, false /* isButton */, !mIsOnLeftEdge);
                // We don't have the end point for logging purposes.
                mEndPoint.x = -1;
                mEndPoint.y = -1;
                mLogGesture = true;
                logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_EXCLUDED);
            }
            return false;
        }

        mInRejectedExclusion = mUnrestrictedExcludeRegion.contains(x, y);
        mLogGesture = true;
        return withinRange;
    }

    public void setImeVisible(boolean visible) {
        mImeVisible = visible;
    }

    private void cancelGesture(MotionEvent ev) {
        // Send action cancel to reset all the touch events
        mHandler.removeCallbacksAndMessages(null);
        mAllowGesture = false;
        mLogGesture = false;
        mInRejectedExclusion = false;
        MotionEvent cancelEv = MotionEvent.obtain(ev);
        cancelEv.setAction(MotionEvent.ACTION_CANCEL);
        mEdgeBackPlugin.onMotionEvent(cancelEv);
        cancelEv.recycle();
    }

    private void logGesture(int backType) {
        if (!mLogGesture) {
            return;
        }
        mLogGesture = false;
        SysUiStatsLog.write(SysUiStatsLog.BACK_GESTURE_REPORTED_REPORTED, backType,
                (int) mDownPoint.y, mIsOnLeftEdge
                        ? SysUiStatsLog.BACK_GESTURE__X_LOCATION__LEFT
                        : SysUiStatsLog.BACK_GESTURE__X_LOCATION__RIGHT,
                (int) mDownPoint.x, (int) mDownPoint.y,
                (int) mEndPoint.x, (int) mEndPoint.y,
                mEdgeWidthLeft + mLeftInset,
                mDisplaySize.x - (mEdgeWidthRight + mRightInset));
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // Verify if this is in within the touch region and we aren't in immersive mode, and
            // either the bouncer is showing or the notification panel is hidden
            mIsOnLeftEdge = ev.getX() <= mEdgeWidthLeft + mLeftInset;
            mLogGesture = false;
            mInRejectedExclusion = false;
            mAllowGesture = !mDisabledForQuickstep && mIsBackGestureAllowed
                    && !mGestureBlockingActivityRunning
                    && !QuickStepContract.isBackGestureDisabled(mSysUiFlags)
                    && isWithinTouchRegion((int) ev.getX(), (int) ev.getY());
            if (mAllowGesture) {
                mEdgeBackPlugin.setIsLeftPanel(mIsOnLeftEdge);
                mEdgeBackPlugin.onMotionEvent(ev);
            }
            if (mLogGesture) {
                mDownPoint.set(ev.getX(), ev.getY());
                mEndPoint.set(-1, -1);
                mThresholdCrossed = false;
            }
        } else if ((mAllowGesture || mLogGesture) && !mBlockNextEvent) {
            if (!mThresholdCrossed) {
                // mThresholdCrossed is true only after the first move event
                // then other events will go straight to "forward touch" line
                mEndPoint.x = (int) ev.getX();
                mEndPoint.y = (int) ev.getY();
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    if (mAllowGesture) {
                        logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_MULTI_TOUCH);
                        // We do not support multi touch for back gesture
                        cancelGesture(ev);
                    }
                    mLogGesture = false;
                    return;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    int elapsedTime = (int)(ev.getEventTime() - ev.getDownTime());
                    if (elapsedTime > mLongPressTimeout) {
                        if (mAllowGesture) {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_LONG_PRESS);
                            cancelGesture(ev);
                        }
                        mLogGesture = false;
                        return;
                    }
                    float dx = Math.abs(ev.getX() - mDownPoint.x);
                    float dy = Math.abs(ev.getY() - mDownPoint.y);
                    if (dy > dx && dy > mTouchSlop) {
                        if (mAllowGesture) {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_VERTICAL_MOVE);
                            cancelGesture(ev);
                        }
                        mLogGesture = false;
                        return;
                    } else if (dx > dy && dx > mTouchSlop) {
                        if (mAllowGesture) {
                            mThresholdCrossed = true;
                        if (!mIsExtendedSwipe && ((mLeftLongSwipeAction != 0 && mIsOnLeftEdge)
                                || (mRightLongSwipeAction != 0 && !mIsOnLeftEdge))) {
                                mLongSwipeAction.setIsVertical(false);
                                mHandler.postDelayed(mLongSwipeAction, (mTimeout - elapsedTime));
                                // mThresholdCrossed is now set to true so on next move event the handler won't get triggered again
                            }
                            // Capture inputs
                            mInputMonitor.pilferPointers();
                        } else {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_FAR_FROM_EDGE);
                        }
                    }
                }
            }

            boolean isUp = action == MotionEvent.ACTION_UP;
            boolean isCancel = action == MotionEvent.ACTION_CANCEL;
            boolean isMove = action == MotionEvent.ACTION_MOVE;
            if (isMove && mIsExtendedSwipe) {
                float deltaX = Math.abs(ev.getX() - mDownPoint.x);
                float deltaY = Math.abs(ev.getY() - mDownPoint.y);
                // give priority to horizontal (X) swipe
                if (deltaX  > (int)((mDisplaySize.x / 4) * 2.5f)) {
                    mLongSwipeAction.setIsVertical(false);
                    mLongSwipeAction.run();
                }
                if (deltaY  > (mDisplaySize.y / 4)) {
                    mLongSwipeAction.setIsVertical(true);
                    mLongSwipeAction.run();
                }
            }
            if (isUp || isCancel) {
                mHandler.removeCallbacksAndMessages(null);
            }

            if (mAllowGesture) {
                // forward touch
                mEdgeBackPlugin.onMotionEvent(ev);
            }
        } else if (mBlockNextEvent) {
            mBlockNextEvent = false;
            cancelGesture(ev);
        }

        Dependency.get(ProtoTracer.class).update();
    }

    private SwipeRunnable mLongSwipeAction = new SwipeRunnable();
    private class SwipeRunnable implements Runnable {
        private boolean mIsVertical;

        public void setIsVertical(boolean vertical) {
            mIsVertical = vertical;
        }

        @Override
        public void run() {
            triggerAction(mIsVertical);
        }
    }

    private void prepareForAction() {
        mBlockNextEvent = true;
        mEdgeBackPlugin.resetOnDown();
        mVibrator.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK));
    }

    private void triggerAction(boolean isVertical) {
        int action = mIsOnLeftEdge ? (isVertical ? mLeftVerticalSwipeAction : mLeftLongSwipeAction)
                : (isVertical ? mRightVerticalSwipeAction : mRightLongSwipeAction);

        if (action == 0) return;

        prepareForAction();
        LineageButtons.getAttachedInstance(mContext).
                triggerAction(action, mIsOnLeftEdge, isVertical, mContext);
    }

    private void updateDisabledForQuickstep() {
        int rotation = mContext.getResources().getConfiguration().windowConfiguration.getRotation();
        mDisabledForQuickstep = mStartingQuickstepRotation > -1 &&
                mStartingQuickstepRotation != rotation;
    }

    @Override
    public void onDisplayAdded(int displayId) { }

    @Override
    public void onDisplayRemoved(int displayId) { }

    @Override
    public void onDisplayChanged(int displayId) {
        if (mStartingQuickstepRotation > -1) {
            updateDisabledForQuickstep();
        }

        if (displayId == mDisplayId) {
            updateDisplaySize();
        }
    }

    private void updateDisplaySize() {
        mContext.getDisplay().getRealSize(mDisplaySize);
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.setDisplaySize(mDisplaySize);
        }
    }

    private void sendEvent(int action, int code) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);

        // Bubble controller will give us a valid display id if it should get the back event
        BubbleController bubbleController = Dependency.get(BubbleController.class);
        int bubbleDisplayId = bubbleController.getExpandedDisplayId(mContext);
        if (code == KeyEvent.KEYCODE_BACK && bubbleDisplayId != INVALID_DISPLAY) {
            ev.setDisplayId(bubbleDisplayId);
        }
        InputManager.getInstance().injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public void setInsets(int leftInset, int rightInset) {
        mLeftInset = leftInset;
        mRightInset = rightInset;
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.setInsets(leftInset, rightInset);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("EdgeBackGestureHandler:");
        pw.println("  mIsEnabled=" + mIsEnabled);
        pw.println("  mIsBackGestureAllowed=" + mIsBackGestureAllowed);
        pw.println("  mAllowGesture=" + mAllowGesture);
        pw.println("  mDisabledForQuickstep=" + mDisabledForQuickstep);
        pw.println("  mStartingQuickstepRotation=" + mStartingQuickstepRotation);
        pw.println("  mInRejectedExclusion" + mInRejectedExclusion);
        pw.println("  mExcludeRegion=" + mExcludeRegion);
        pw.println("  mUnrestrictedExcludeRegion=" + mUnrestrictedExcludeRegion);
        pw.println("  mIsAttached=" + mIsAttached);
        pw.println("  mEdgeWidthLeft=" + mEdgeWidthLeft);
        pw.println("  mEdgeWidthRight=" + mEdgeWidthRight);
    }

    private boolean isGestureBlockingActivityRunning() {
        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        ComponentName topActivity = runningTask == null ? null : runningTask.topActivity;
        return topActivity != null && mGestureBlockingActivities.contains(topActivity);
    }

    @Override
    public void writeToProto(SystemUiTraceProto proto) {
        if (proto.edgeBackGestureHandler == null) {
            proto.edgeBackGestureHandler = new EdgeBackGestureHandlerProto();
        }
        proto.edgeBackGestureHandler.allowGesture = mAllowGesture;
    }

    class SysUiInputEventReceiver extends InputEventReceiver {
        SysUiInputEventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        public void onInputEvent(InputEvent event) {
            EdgeBackGestureHandler.this.onInputEvent(event);
            finishInputEvent(event, true);
        }
    }
}
