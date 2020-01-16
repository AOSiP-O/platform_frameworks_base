/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.Utils;
import com.android.settingslib.development.DevelopmentSettingsEnabler;
import com.android.settingslib.drawable.UserIconDrawable;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.R.dimen;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.statusbar.phone.MultiUserSwitch;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import static android.content.Context.VIBRATOR_SERVICE;

import javax.inject.Inject;
import javax.inject.Named;

public class QSFooterImpl extends FrameLayout implements QSFooter,
        OnClickListener, OnLongClickListener , OnUserInfoChangedListener, Tunable {

    private static final String TAG = "QSFooterImpl";
    public static final String QS_SHOW_DRAG_HANDLE = "qs_show_drag_handle";

    private final ActivityStarter mActivityStarter;
    private final UserInfoController mUserInfoController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private SettingsButton mSettingsButton;
    protected View mSettingsContainer;
    private PageIndicator mPageIndicator;

    private boolean mQsDisabled;
    private QSPanel mQsPanel;

    private boolean mExpanded;

    private boolean mListening;

    protected MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;

    protected TouchAnimator mFooterAnimator;
    private float mExpansionAmount;

    protected View mEdit;
    protected View mEditContainer;
    private TouchAnimator mSettingsCogAnimator;

    private View mActionsContainer;
    private View mDragHandle;

    private OnClickListener mExpandClickListener;

    private Vibrator mVibrator;

    @Inject
    public QSFooterImpl(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            ActivityStarter activityStarter, UserInfoController userInfoController,
            DeviceProvisionedController deviceProvisionedController) {
        super(context, attrs);
        mActivityStarter = activityStarter;
        mUserInfoController = userInfoController;
        mDeviceProvisionedController = deviceProvisionedController;
        mVibrator = (Vibrator) getContext().getSystemService(VIBRATOR_SERVICE);
    }

    @VisibleForTesting
    public QSFooterImpl(Context context, AttributeSet attrs) {
        this(context, attrs,
                Dependency.get(ActivityStarter.class),
                Dependency.get(UserInfoController.class),
                Dependency.get(DeviceProvisionedController.class));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mEdit = findViewById(android.R.id.edit);
        mEdit.setOnClickListener(view ->
                mActivityStarter.postQSRunnableDismissingKeyguard(() ->
                        mQsPanel.showEdit(view)));

        mPageIndicator = findViewById(R.id.footer_page_indicator);

        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsContainer = findViewById(R.id.settings_button_container);
        mSettingsButton.setOnClickListener(this);
        mSettingsButton.setOnLongClickListener(this);

        mMultiUserSwitch = findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = mMultiUserSwitch.findViewById(R.id.multi_user_avatar);

        mDragHandle = findViewById(R.id.qs_drag_handle_view);
        mActionsContainer = findViewById(R.id.qs_footer_actions_container);
        mEditContainer = findViewById(R.id.qs_footer_actions_edit_container);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);

        updateResources();

        addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> updateAnimator(right - left));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateEverything();
    }

    private void setBuildText() {
        // TODO keeping this here - maybe we find some more useful use for that space
        TextView v = findViewById(R.id.build);
        if (v == null) return;
        v.setVisibility(View.GONE);
    }

    private void updateAnimator(int width) {
        mSettingsCogAnimator = new Builder()
                .addFloat(mSettingsButton, "rotation", -120, 0)
                .build();

        setExpansion(mExpansionAmount);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        updateFooterAnimator();
    }

    private void updateFooterAnimator() {
        mFooterAnimator = createFooterAnimator();
    }

    @Nullable
    private TouchAnimator createFooterAnimator() {
        return new TouchAnimator.Builder()
                .addFloat(mActionsContainer, "alpha", 1, 1)
                .addFloat(mMultiUserAvatar, "alpha", 0, 1)
                .addFloat(mEditContainer, "alpha", 0, 1)
                .addFloat(mDragHandle, "alpha", 0, 0, 0)
                .addFloat(mPageIndicator, "alpha", 0, 1)
                .setStartDelay(0.15f)
                .build();
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        setExpansion(mExpansionAmount);
    }

    @Override
    public void setExpandClickListener(OnClickListener onClickListener) {
        mExpandClickListener = onClickListener;
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        updateEverything();
    }

    @Override
    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        if (mSettingsCogAnimator != null) mSettingsCogAnimator.setPosition(headerExpansionFraction);

        if (mFooterAnimator != null) {
            mFooterAnimator.setPosition(headerExpansionFraction);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, QS_SHOW_DRAG_HANDLE);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        Dependency.get(TunerService.class).removeTunable(this);
        setListening(false);
        super.onDetachedFromWindow();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_DRAG_HANDLE.equals(key)) {
            setHideDragHandle(newValue != null && Integer.parseInt(newValue) == 0);
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;
        updateListeners();
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
            if (mExpandClickListener != null) {
                mExpandClickListener.onClick(null);
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateEverything();
    }

    public void updateEverything() {
        post(() -> {
            updateVisibilities();
            updateClickabilities();
            setClickable(false);
        });
    }

    private void updateClickabilities() {
        mMultiUserSwitch.setClickable(mMultiUserSwitch.getVisibility() == View.VISIBLE);
        mEdit.setClickable(mEdit.getVisibility() == View.VISIBLE);
        mSettingsButton.setClickable(mSettingsButton.getVisibility() == View.VISIBLE);
    }

    private void updateVisibilities() {
        mSettingsContainer.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        final boolean isDemo = UserManager.isDeviceInDemoMode(mContext);
        mMultiUserSwitch.setVisibility(showUserSwitcher() ? View.VISIBLE : View.INVISIBLE);
        mEditContainer.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
        mSettingsButton.setVisibility(isDemo || !mExpanded ? View.VISIBLE : View.VISIBLE);
    }

    private boolean showUserSwitcher() {
        return mExpanded && mMultiUserSwitch.isMultiUserEnabled();
    }

    private void updateListeners() {
        if (mListening) {
            mUserInfoController.addCallback(this);
        } else {
            mUserInfoController.removeCallback(this);
        }
    }

    @Override
    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        if (mQsPanel != null) {
            mMultiUserSwitch.setQsPanel(qsPanel);
            mQsPanel.setFooterPageIndicator(mPageIndicator);
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            if (!mDeviceProvisionedController.isCurrentUserSetup()) {
                // If user isn't setup just unlock the device and dump them back at SUW.
                mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                });
                return;
            }
            MetricsLogger.action(mContext,
                    mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                            : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
            startSettingsActivity();
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mSettingsButton) {
            startOwlsNestActivity();
            mVibrator.vibrate(30);
        }
        return false;
    }

    private ComponentName OWLSNEST_SETTING_COMPONENT = new ComponentName(
            "com.android.settings", "com.android.settings.Settings$OwlsNestSettingsActivity");

    private void startOwlsNestActivity() {
        mActivityStarter.startActivity(new Intent().setComponent(OWLSNEST_SETTING_COMPONENT),
                true /* dismissShade */);
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        if (picture != null &&
                UserManager.get(mContext).isGuestUser(KeyguardUpdateMonitor.getCurrentUser()) &&
                !(picture instanceof UserIconDrawable)) {
            picture = picture.getConstantState().newDrawable(mContext.getResources()).mutate();
            picture.setColorFilter(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorForeground),
                    Mode.SRC_IN);
        }
        mMultiUserAvatar.setImageDrawable(picture);
    }

    private void setHideDragHandle(boolean hide) {
        mDragHandle.setVisibility(hide ? View.GONE : View.VISIBLE);
    }
}
