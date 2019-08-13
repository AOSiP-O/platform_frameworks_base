/*
 * Copyright (C) 2019 PixelExperience
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program;
 * if not, see <http://www.gnu.org/licenses>.
 */

package com.android.internal.custom.screenshot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

public class StitchImageUtility {
    public static final String STITCHIMAGE_APP_PACKAGE_NAME = "com.asus.stitchimage";
    public static final String STITCHIMAGE_FILEPROVIDER_CLASS = "com.asus.stitchimage.fileprovider";
    private static final String STITCHIMAGE_OVERLAY_SERVICE_CLASS = "com.asus.stitchimage.OverlayService";
    private static final String STITCHIMAGE_SERVICE_PACKAGE_NAME = "com.asus.stitchimage.service";
    private static final String EXTRA_KEY_STITCHIMAGE_SETTINGS_CALLFROM = "callfrom";
    private static final String EXTRA_VALUE_STITCHIMAGE_SETTINGS_CALLFROM_ASUSSETTINGS = "AsusSettings";
    private static String TAG = "StitchImageUtility";
    private boolean mSupportLongScreenshot = false;
    private final Context mContext;
    private Ringtone mScreenshotSound;

    public StitchImageUtility(Context context) {
        mContext = context;
        mScreenshotSound= RingtoneManager.getRingtone(mContext,
                    Uri.parse("file://" + "/system/media/audio/ui/camera_click.ogg"));
        mSupportLongScreenshot = isAvailable();
    }

    public boolean takeScreenShot() {
        if (mSupportLongScreenshot) {
            try {
                Log.i(TAG, "Take long screenshot.");
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(STITCHIMAGE_APP_PACKAGE_NAME, STITCHIMAGE_OVERLAY_SERVICE_CLASS));
                intent.putExtra(EXTRA_KEY_STITCHIMAGE_SETTINGS_CALLFROM, EXTRA_VALUE_STITCHIMAGE_SETTINGS_CALLFROM_ASUSSETTINGS);
                mContext.startService(intent);
                playScreenshotSound();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "trigger stitchimage failed, Exception :" + e);
            }
        }
        return false;
    }

    private void playScreenshotSound(){
        if (Settings.System.getIntForUser(mContext.getContentResolver(), Settings.System.SCREENSHOT_SOUND, 1, UserHandle.USER_CURRENT) == 1) {
            if (mScreenshotSound != null) {
                mScreenshotSound.play();
            }
        }
    }

    private boolean isAvailable() {
        try {
            mContext.getPackageManager().getPackageInfo(STITCHIMAGE_APP_PACKAGE_NAME, 0);
            mContext.getPackageManager().getPackageInfo(STITCHIMAGE_SERVICE_PACKAGE_NAME, 0);
            return true;
        } catch (NameNotFoundException unused) {
        }
        return false;
    }
}
