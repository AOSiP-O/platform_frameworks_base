/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.dialog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import com.android.settingslib.media.MediaOutputSliceConstants
import javax.inject.Inject

/**
 * BroadcastReceiver for handling media output intent
 */
class MediaOutputDialogReceiver @Inject constructor(
    private val mediaOutputDialogFactory: MediaOutputDialogFactory
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (TextUtils.equals(MediaOutputSliceConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG,
                        intent.action)) {
            mediaOutputDialogFactory.create(
                    intent.getStringExtra(MediaOutputSliceConstants.EXTRA_PACKAGE_NAME), false)
        }
    }
}
