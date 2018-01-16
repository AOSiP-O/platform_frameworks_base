/*
 * Copyright 2018 The Android Open Source Project
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

package android.media.update;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.widget.MediaController2;
import android.widget.VideoView2;

import java.util.Map;

/**
 * Interface for connecting the public API to an updatable implementation.
 *
 * Each instance object is connected to one corresponding updatable object which implements the
 * runtime behavior of that class. There should a corresponding provider method for all public
 * methods.
 *
 * All methods behave as per their namesake in the public API.
 *
 * @see android.widget.VideoView2
 *
 * @hide
 */
// TODO @SystemApi
public interface VideoView2Provider extends ViewProvider {
    void start_impl();
    void pause_impl();
    int getDuration_impl();
    int getCurrentPosition_impl();
    void seekTo_impl(int msec);
    boolean isPlaying_impl();
    int getBufferPercentage_impl();
    int getAudioSessionId_impl();
    void showSubtitle_impl();
    void hideSubtitle_impl();
    void setAudioFocusRequest_impl(int focusGain);
    void setAudioAttributes_impl(AudioAttributes attributes);
    void setVideoPath_impl(String path);
    void setVideoURI_impl(Uri uri);
    void setVideoURI_impl(Uri uri, Map<String, String> headers);
    void setMediaController2_impl(MediaController2 controllerView);
    void setViewType_impl(int viewType);
    int getViewType_impl();
    void stopPlayback_impl();
    void setOnPreparedListener_impl(MediaPlayer.OnPreparedListener l);
    void setOnCompletionListener_impl(MediaPlayer.OnCompletionListener l);
    void setOnErrorListener_impl(MediaPlayer.OnErrorListener l);
    void setOnInfoListener_impl(MediaPlayer.OnInfoListener l);
    void setOnViewTypeChangedListener_impl(VideoView2.OnViewTypeChangedListener l);
}
