package com.android.server.policy.keyguard;

import static android.view.Display.INVALID_DISPLAY;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManagerPolicy.OnKeyguardExitResult;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardDrawnCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.server.UiThread;

import java.io.PrintWriter;

/**
 * A local class that keeps a cache of keyguard state that can be restored in the event
 * keyguard crashes. It currently also allows runtime-selectable
 * local or remote instances of keyguard.
 */
public class KeyguardServiceDelegate {
    private static final String TAG = "KeyguardServiceDelegate";
    private static final boolean DEBUG = false;

    private static final int SCREEN_STATE_OFF = 0;
    private static final int SCREEN_STATE_TURNING_ON = 1;
    private static final int SCREEN_STATE_ON = 2;
    private static final int SCREEN_STATE_TURNING_OFF = 3;

    private static final int INTERACTIVE_STATE_SLEEP = 0;
    private static final int INTERACTIVE_STATE_WAKING = 1;
    private static final int INTERACTIVE_STATE_AWAKE = 2;
    private static final int INTERACTIVE_STATE_GOING_TO_SLEEP = 3;

    protected KeyguardServiceWrapper mKeyguardService;
    private final Context mContext;
    private final Handler mHandler;
    private final KeyguardState mKeyguardState = new KeyguardState();
    private final KeyguardStateMonitor.StateCallback mCallback;

    private DrawnListener mDrawnListenerWhenConnect;

    private static final class KeyguardState {
        KeyguardState() {
            reset();
        }
        boolean showing;
        boolean showingAndNotOccluded;
        boolean inputRestricted;
        boolean occluded;
        boolean secure;
        boolean dreaming;
        boolean systemIsReady;
        boolean deviceHasKeyguard;
        public boolean enabled;
        public int offReason;
        public int currentUser;
        public boolean bootCompleted;
        public int screenState;
        public int interactiveState;

        private void reset() {
            // Assume keyguard is showing and secure until we know for sure. This is here in
            // the event something checks before the service is actually started.
            // KeyguardService itself should default to this state until the real state is known.
            showing = true;
            showingAndNotOccluded = true;
            secure = true;
            deviceHasKeyguard = true;
            enabled = true;
            currentUser = UserHandle.USER_NULL;
        }
    };

    public interface DrawnListener {
        void onDrawn();
    }

    // A delegate class to map a particular invocation with a ShowListener object.
    private final class KeyguardShowDelegate extends IKeyguardDrawnCallback.Stub {
        private DrawnListener mDrawnListener;

        KeyguardShowDelegate(DrawnListener drawnListener) {
            mDrawnListener = drawnListener;
        }

        @Override
        public void onDrawn() throws RemoteException {
            if (DEBUG) Log.v(TAG, "**** SHOWN CALLED ****");
            if (mDrawnListener != null) {
                mDrawnListener.onDrawn();
            }
        }
    };

    // A delegate class to map a particular invocation with an OnKeyguardExitResult object.
    private final class KeyguardExitDelegate extends IKeyguardExitCallback.Stub {
        private OnKeyguardExitResult mOnKeyguardExitResult;

        KeyguardExitDelegate(OnKeyguardExitResult onKeyguardExitResult) {
            mOnKeyguardExitResult = onKeyguardExitResult;
        }

        @Override
        public void onKeyguardExitResult(boolean success) throws RemoteException {
            if (DEBUG) Log.v(TAG, "**** onKeyguardExitResult(" + success +") CALLED ****");
            if (mOnKeyguardExitResult != null) {
                mOnKeyguardExitResult.onKeyguardExitResult(success);
            }
        }
    };

    public KeyguardServiceDelegate(Context context, KeyguardStateMonitor.StateCallback callback) {
        mContext = context;
        mHandler = UiThread.getHandler();
        mCallback = callback;
    }

    public void bindService(Context context) {
        Intent intent = new Intent();
        final Resources resources = context.getApplicationContext().getResources();

        final ComponentName keyguardComponent = ComponentName.unflattenFromString(
                resources.getString(com.android.internal.R.string.config_keyguardComponent));
        intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
        intent.setComponent(keyguardComponent);

        if (!context.bindServiceAsUser(intent, mKeyguardConnection,
                Context.BIND_AUTO_CREATE, mHandler, UserHandle.SYSTEM)) {
            Log.v(TAG, "*** Keyguard: can't bind to " + keyguardComponent);
            mKeyguardState.showing = false;
            mKeyguardState.showingAndNotOccluded = false;
            mKeyguardState.secure = false;
            synchronized (mKeyguardState) {
                // TODO: Fix synchronisation model in this class. The other state in this class
                // is at least self-healing but a race condition here can lead to the scrim being
                // stuck on keyguard-less devices.
                mKeyguardState.deviceHasKeyguard = false;
            }
        } else {
            if (DEBUG) Log.v(TAG, "*** Keyguard started");
        }
    }

    private final ServiceConnection mKeyguardConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.v(TAG, "*** Keyguard connected (yay!)");
            mKeyguardService = new KeyguardServiceWrapper(mContext,
                    IKeyguardService.Stub.asInterface(service), mCallback);
            if (mKeyguardState.systemIsReady) {
                // If the system is ready, it means keyguard crashed and restarted.
                mKeyguardService.onSystemReady();
                if (mKeyguardState.currentUser != UserHandle.USER_NULL) {
                    // There has been a user switch earlier
                    mKeyguardService.setCurrentUser(mKeyguardState.currentUser);
                }
                // This is used to hide the scrim once keyguard displays.
                if (mKeyguardState.interactiveState == INTERACTIVE_STATE_AWAKE
                        || mKeyguardState.interactiveState == INTERACTIVE_STATE_WAKING) {
                    mKeyguardService.onStartedWakingUp();
                }
                if (mKeyguardState.interactiveState == INTERACTIVE_STATE_AWAKE) {
                    mKeyguardService.onFinishedWakingUp();
                }
                if (mKeyguardState.screenState == SCREEN_STATE_ON
                        || mKeyguardState.screenState == SCREEN_STATE_TURNING_ON) {
                    mKeyguardService.onScreenTurningOn(
                            new KeyguardShowDelegate(mDrawnListenerWhenConnect));
                }
                if (mKeyguardState.screenState == SCREEN_STATE_ON) {
                    mKeyguardService.onScreenTurnedOn();
                }
                mDrawnListenerWhenConnect = null;
            }
            if (mKeyguardState.bootCompleted) {
                mKeyguardService.onBootCompleted();
            }
            if (mKeyguardState.occluded) {
                mKeyguardService.setOccluded(mKeyguardState.occluded, false /* animate */);
            }
            if (!mKeyguardState.enabled) {
                mKeyguardService.setKeyguardEnabled(mKeyguardState.enabled);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.v(TAG, "*** Keyguard disconnected (boo!)");
            mKeyguardService = null;
            mKeyguardState.reset();
            mHandler.post(() -> {
                try {
                    // There are no longer any keyguard windows on secondary displays, so pass
                    // INVALID_DISPLAY. All that means is that showWhenLocked activities on
                    // secondary displays now get to show.
                    ActivityManager.getService().setLockScreenShown(true, INVALID_DISPLAY);
                } catch (RemoteException e) {
                    // Local call.
                }
            });
        }
    };

    public boolean isShowing() {
        if (mKeyguardService != null) {
            mKeyguardState.showing = mKeyguardService.isShowing();
        }
        return mKeyguardState.showing;
    }

    public boolean isTrusted() {
        if (mKeyguardService != null) {
            return mKeyguardService.isTrusted();
        }
        return false;
    }

    public boolean hasLockscreenWallpaper() {
        if (mKeyguardService != null) {
            return mKeyguardService.hasLockscreenWallpaper();
        }
        return false;
    }

    public boolean isInputRestricted() {
        if (mKeyguardService != null) {
            mKeyguardState.inputRestricted = mKeyguardService.isInputRestricted();
        }
        return mKeyguardState.inputRestricted;
    }

    public void verifyUnlock(final OnKeyguardExitResult onKeyguardExitResult) {
        if (mKeyguardService != null) {
            mKeyguardService.verifyUnlock(new KeyguardExitDelegate(onKeyguardExitResult));
        }
    }

    public void setOccluded(boolean isOccluded, boolean animate) {
        if (mKeyguardService != null) {
            if (DEBUG) Log.v(TAG, "setOccluded(" + isOccluded + ") animate=" + animate);
            mKeyguardService.setOccluded(isOccluded, animate);
        }
        mKeyguardState.occluded = isOccluded;
    }

    public void dismiss(IKeyguardDismissCallback callback) {
        if (mKeyguardService != null) {
            mKeyguardService.dismiss(callback);
        }
    }

    public boolean isSecure(int userId) {
        if (mKeyguardService != null) {
            mKeyguardState.secure = mKeyguardService.isSecure(userId);
        }
        return mKeyguardState.secure;
    }

    public void onDreamingStarted() {
        if (mKeyguardService != null) {
            mKeyguardService.onDreamingStarted();
        }
        mKeyguardState.dreaming = true;
    }

    public void onDreamingStopped() {
        if (mKeyguardService != null) {
            mKeyguardService.onDreamingStopped();
        }
        mKeyguardState.dreaming = false;
    }

    public void onStartedWakingUp() {
        if (mKeyguardService != null) {
            if (DEBUG) Log.v(TAG, "onStartedWakingUp()");
            mKeyguardService.onStartedWakingUp();
        }
        mKeyguardState.interactiveState = INTERACTIVE_STATE_WAKING;
    }

    public void onFinishedWakingUp() {
        if (mKeyguardService != null) {
            if (DEBUG) Log.v(TAG, "onFinishedWakingUp()");
            mKeyguardService.onFinishedWakingUp();
        }
        mKeyguardState.interactiveState = INTERACTIVE_STATE_AWAKE;
    }

    public void onScreenTurningOff() {
        if (mKeyguardService != null) {
            if (DEBUG) Log.v(TAG, "onScreenTurningOff()");
            mKeyguardService.onScreenTurningOff();
        }
        mKeyguardState.screenState = SCREEN_STATE_TURNING_OFF;
    }

    public void onScreenTurnedOff() {
        if (mKeyguardService != null) {
            if (DEBUG) Log.v(TAG, "onScreenTurnedOff()");
            mKeyguardService.onScreenTurnedOff();
        }
        mKeyguardState.screenState = SCREEN_STATE_OFF;
    }

    public void onScreenTurningOn(final DrawnListener drawnListener) {
        if (mKeyguardService != null) {
            if (DEBUG) Log.v(TAG, "onScreenTurnedOn(showListener = " + drawnListener + ")");
            mKeyguardService.onScreenTurningOn(new KeyguardShowDelegate(drawnListener));
        } else {
            // try again when we establish a connection
            Slog.w(TAG, "onScreenTurningOn(): no keyguard service!");
            // This shouldn't happen, but if it does, show the scrim immediately and
            // invoke the listener's callback after the service actually connects.
            mDrawnListenerWhenConnect = drawnListener;
        }
        mKeyguardState.screenState = SCREEN_STATE_TURNING_ON;
    }

    public void onScreenTurnedOn() {
        if (mKeyguardService != null) {
            if (DEBUG) Log.v(TAG, "onScreenTurnedOn()");
            mKeyguardService.onScreenTurnedOn();
        }
        mKeyguardState.screenState = SCREEN_STATE_ON;
    }

    public void onStartedGoingToSleep(int why) {
        if (mKeyguardService != null) {
            mKeyguardService.onStartedGoingToSleep(why);
        }
        mKeyguardState.offReason = why;
        mKeyguardState.interactiveState = INTERACTIVE_STATE_GOING_TO_SLEEP;
    }

    public void onFinishedGoingToSleep(int why, boolean cameraGestureTriggered) {
        if (mKeyguardService != null) {
            mKeyguardService.onFinishedGoingToSleep(why, cameraGestureTriggered);
        }
        mKeyguardState.interactiveState = INTERACTIVE_STATE_SLEEP;
    }

    public void setKeyguardEnabled(boolean enabled) {
        if (mKeyguardService != null) {
            mKeyguardService.setKeyguardEnabled(enabled);
        }
        mKeyguardState.enabled = enabled;
    }

    public void onSystemReady() {
        if (mKeyguardService != null) {
            mKeyguardService.onSystemReady();
        } else {
            mKeyguardState.systemIsReady = true;
        }
    }

    public void doKeyguardTimeout(Bundle options) {
        if (mKeyguardService != null) {
            mKeyguardService.doKeyguardTimeout(options);
        }
    }

    public void setCurrentUser(int newUserId) {
        if (mKeyguardService != null) {
            mKeyguardService.setCurrentUser(newUserId);
        }
        mKeyguardState.currentUser = newUserId;
    }

    public void setSwitchingUser(boolean switching) {
        if (mKeyguardService != null) {
            mKeyguardService.setSwitchingUser(switching);
        }
    }

    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        if (mKeyguardService != null) {
            mKeyguardService.startKeyguardExitAnimation(startTime, fadeoutDuration);
        }
    }

    public void onBootCompleted() {
        if (mKeyguardService != null) {
            mKeyguardService.onBootCompleted();
        }
        mKeyguardState.bootCompleted = true;
    }

    public void onShortPowerPressedGoHome() {
        if (mKeyguardService != null) {
            mKeyguardService.onShortPowerPressedGoHome();
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + TAG);
        prefix += "  ";
        pw.println(prefix + "showing=" + mKeyguardState.showing);
        pw.println(prefix + "showingAndNotOccluded=" + mKeyguardState.showingAndNotOccluded);
        pw.println(prefix + "inputRestricted=" + mKeyguardState.inputRestricted);
        pw.println(prefix + "occluded=" + mKeyguardState.occluded);
        pw.println(prefix + "secure=" + mKeyguardState.secure);
        pw.println(prefix + "dreaming=" + mKeyguardState.dreaming);
        pw.println(prefix + "systemIsReady=" + mKeyguardState.systemIsReady);
        pw.println(prefix + "deviceHasKeyguard=" + mKeyguardState.deviceHasKeyguard);
        pw.println(prefix + "enabled=" + mKeyguardState.enabled);
        pw.println(prefix + "offReason=" + mKeyguardState.offReason);
        pw.println(prefix + "currentUser=" + mKeyguardState.currentUser);
        pw.println(prefix + "bootCompleted=" + mKeyguardState.bootCompleted);
        pw.println(prefix + "screenState=" + mKeyguardState.screenState);
        pw.println(prefix + "interactiveState=" + mKeyguardState.interactiveState);
        if (mKeyguardService != null) {
            mKeyguardService.dump(prefix, pw);
        }
    }
}
