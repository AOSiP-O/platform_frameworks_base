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

package com.android.systemui.fingerprint;

import android.app.KeyguardManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View.OnTouchListener;
import android.view.View;
import android.widget.ImageView;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.util.Log;
import android.util.Slog;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.systemui.R;

import java.io.PrintWriter;

import vendor.oneplus.hardware.display.V1_0.IOneplusDisplay;

public class FODCircleView extends ImageView implements OnTouchListener {
    private final int mX, mY, mW, mH;
    private final Paint mPaintFingerprint = new Paint();
    private final Paint mPaintShow = new Paint();
    private IOneplusDisplay mDisplayDaemon = null;
    private boolean mInsideCircle = false;
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();

    private final int DISPLAY_AOD_MODE = 8;
    private final int DISPLAY_APPLY_HIDE_AOD = 11;
    private final int DISPLAY_NOTIFY_PRESS = 9;
    private final int DISPLAY_SET_DIM = 10;

    private final WindowManager mWM;

    private boolean mIsDreaming;
    private boolean mIsPulsing;
    private boolean mIsScreenOn;
    private boolean mChange;

    public boolean viewAdded;
    private boolean mIsEnrolling;

    KeyguardUpdateMonitor mUpdateMonitor;

    KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            super.onDreamingStateChanged(dreaming);
            mIsDreaming = dreaming;
            mInsideCircle = false;
            mChange = true;
        }

        @Override
        public void onPulsing(boolean pulsing) {
            super.onPulsing(pulsing);
            mIsPulsing = pulsing;
            mInsideCircle = false;
            mChange = true;
        }

        @Override
        public void onScreenTurnedOff() {
            super.onScreenTurnedOff();
            mInsideCircle = false;
            mChange = true;
        }

        @Override
        public void onStartedGoingToSleep(int why) {
            super.onStartedGoingToSleep(why);
            mInsideCircle = false;
            mChange = true;
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            super.onFinishedGoingToSleep(why);
            mChange = true;
        }

        @Override
        public void onStartedWakingUp() {
            super.onStartedWakingUp();
            mChange = true;
        }

        @Override
        public void onScreenTurnedOn() {
           super.onScreenTurnedOn();
           mIsScreenOn = true;
           mInsideCircle = false;
           mChange = true;
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            super.onKeyguardVisibilityChanged(showing);
            mInsideCircle = false;
            mChange = true;
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            if (viewAdded && isBouncer) {
                hide();
            } else if (!viewAdded) {
                show();
            }
            mChange = true;
        }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            super.onStrongAuthStateChanged(userId);
            mChange = true;
        }

        @Override
        public void onFingerprintAuthenticated(int userId) {
            super.onFingerprintAuthenticated(userId);
            mInsideCircle = false;
            mChange = true;
        }
    };

    FODCircleView(Context context) {
        super(context);

        String[] location = android.os.SystemProperties.get("persist.vendor.sys.fp.fod.location.X_Y", "").split(",");
        String[] size = android.os.SystemProperties.get("persist.vendor.sys.fp.fod.size.width_height", "").split(",");
        if(size.length == 2 && location.length == 2) {
            mX = Integer.parseInt(location[0]);
            mY = Integer.parseInt(location[1]);
            mW = Integer.parseInt(size[0]);
            mH = Integer.parseInt(size[1]);
        } else {
            mX = -1;
            mY = -1;
            mW = -1;
            mH = -1;
        }

	mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprint.setColor(Color.GREEN);

        setImageResource(R.drawable.fod_icon_default);

        mPaintShow.setAntiAlias(true);
        mPaintShow.setColor(Color.argb(0x18, 0x00, 0xff, 0x00));
        setOnTouchListener(this);
        mWM = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);

        try {
            mDisplayDaemon = IOneplusDisplay.getService();
        } catch (Exception e) {}

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        //TODO w!=h?
        if(mInsideCircle) {
            canvas.drawCircle(mW/2, mH/2, (float) (mW/2.0f), this.mPaintFingerprint);
            try {
                mDisplayDaemon.setMode(DISPLAY_NOTIFY_PRESS, 1);
            } catch (RemoteException e) {}
        } else {
            if (mChange) {
                try {
                    mDisplayDaemon.setMode(DISPLAY_NOTIFY_PRESS, 0);
                } catch (RemoteException e) {}
            }

        }
        mChange = false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newInside = (x > 0 && x < mW) && (y > 0 && y < mW);

        if(event.getAction() == MotionEvent.ACTION_UP) {
            newInside = false;
            setImageResource(R.drawable.fod_icon_default);
        }

        if(newInside == mInsideCircle) return mInsideCircle;

        mInsideCircle = newInside;

        invalidate();

        if(!mInsideCircle) {
            setImageResource(R.drawable.fod_icon_default);
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            setImageResource(R.drawable.fod_icon_empty);
        }
        return true;
    }

    public void show() {
        show(false);
    }

    public void show(boolean isEnrolling) {
        if (!isEnrolling && (!mUpdateMonitor.isUnlockWithFingerprintPossible(KeyguardUpdateMonitor.getCurrentUser()) ||
            !mUpdateMonitor.isUnlockingWithFingerprintAllowed())) {
            return;
        }
        if(mX == -1 || mY == -1 || mW == -1 || mH == -1) return;

        mParams.x = mX;
        mParams.y = mY;

        mParams.height = mW;
        mParams.width = mH;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
        mParams.setTitle("Fingerprint on display");
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
            WindowManager.LayoutParams.FLAG_DIM_BEHIND |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        mParams.packageName = "android";

        setImageResource(R.drawable.fod_icon_default);
        mIsEnrolling = isEnrolling;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;
        mWM.addView(this, mParams);
        viewAdded = true;
        mChange = true;
        setDim(true);
    }

    public void hide() {
        if(mX == -1 || mY == -1 || mW == -1 || mH == -1) return;

        mInsideCircle = false;
        mWM.removeView(this);
        viewAdded = false;
        setDim(false);
    }

    private void setDim(boolean dim) {
        if (dim) {
            try {
                mDisplayDaemon.setMode(DISPLAY_AOD_MODE, 2);
                mDisplayDaemon.setMode(DISPLAY_SET_DIM, 1);
            } catch (RemoteException e) {}
            int curBrightness = Settings.System.getInt(getContext().getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, 100);
            float dimAmount = ((float) Math.pow(2, 1.0f - ((float) curBrightness) / 255.0f)) - 1.2f;
            if (dimAmount < 0) dimAmount = 0f;
            mParams.screenBrightness = 1.0f;
            mParams.dimAmount = dimAmount;
        } else {
            try {
                mDisplayDaemon.setMode(DISPLAY_AOD_MODE, 0);
                mDisplayDaemon.setMode(DISPLAY_SET_DIM, 0);
            } catch (RemoteException e) {}
            mParams.screenBrightness = .0f;
            mParams.dimAmount = .0f;
        }
        mWM.updateViewLayout(this, mParams);
    }
}
