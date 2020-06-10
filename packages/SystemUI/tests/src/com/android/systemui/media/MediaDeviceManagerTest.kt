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

package com.android.systemui.media

import android.app.Notification
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.MediaRouter2Manager
import android.media.RoutingSessionInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest

import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock

import com.google.common.truth.Truth.assertThat

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private const val KEY = "TEST_KEY"
private const val KEY_OLD = "TEST_KEY_OLD"
private const val PACKAGE = "PKG"
private const val SESSION_KEY = "SESSION_KEY"
private const val SESSION_ARTIST = "SESSION_ARTIST"
private const val SESSION_TITLE = "SESSION_TITLE"
private const val DEVICE_NAME = "DEVICE_NAME"

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
public class MediaDeviceManagerTest : SysuiTestCase() {

    private lateinit var manager: MediaDeviceManager
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var lmmFactory: LocalMediaManagerFactory
    @Mock private lateinit var lmm: LocalMediaManager
    @Mock private lateinit var mr2: MediaRouter2Manager
    private lateinit var fakeExecutor: FakeExecutor
    @Mock private lateinit var listener: MediaDeviceManager.Listener
    @Mock private lateinit var device: MediaDevice
    @Mock private lateinit var icon: Drawable
    @Mock private lateinit var route: RoutingSessionInfo
    private lateinit var session: MediaSession
    private lateinit var metadataBuilder: MediaMetadata.Builder
    private lateinit var playbackBuilder: PlaybackState.Builder
    private lateinit var notifBuilder: Notification.Builder
    private lateinit var mediaData: MediaData
    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        fakeExecutor = FakeExecutor(FakeSystemClock())
        manager = MediaDeviceManager(context, lmmFactory, mr2, fakeExecutor, mediaDataManager)
        manager.addListener(listener)

        // Configure mocks.
        whenever(device.name).thenReturn(DEVICE_NAME)
        whenever(device.iconWithoutBackground).thenReturn(icon)
        whenever(lmmFactory.create(PACKAGE)).thenReturn(lmm)
        whenever(lmm.getCurrentConnectedDevice()).thenReturn(device)
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(route)

        // Create a media sesssion and notification for testing.
        metadataBuilder = MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
            putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
        }
        playbackBuilder = PlaybackState.Builder().apply {
            setState(PlaybackState.STATE_PAUSED, 6000L, 1f)
            setActions(PlaybackState.ACTION_PLAY)
        }
        session = MediaSession(context, SESSION_KEY).apply {
            setMetadata(metadataBuilder.build())
            setPlaybackState(playbackBuilder.build())
        }
        session.setActive(true)
        notifBuilder = Notification.Builder(context, "NONE").apply {
            setContentTitle(SESSION_TITLE)
            setContentText(SESSION_ARTIST)
            setSmallIcon(android.R.drawable.ic_media_pause)
            setStyle(Notification.MediaStyle().setMediaSession(session.getSessionToken()))
        }
        mediaData = MediaData(true, 0, PACKAGE, null, null, SESSION_TITLE, null,
            emptyList(), emptyList(), PACKAGE, session.sessionToken, null, null, null)
    }

    @After
    fun tearDown() {
        session.release()
    }

    @Test
    fun removeUnknown() {
        manager.onMediaDataRemoved("unknown")
    }

    @Test
    fun loadMediaData() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        verify(lmmFactory).create(PACKAGE)
    }

    @Test
    fun loadAndRemoveMediaData() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        manager.onMediaDataRemoved(KEY)
        verify(lmm).unregisterCallback(any())
    }

    @Test
    fun loadMediaDataWithNullToken() {
        manager.onMediaDataLoaded(KEY, null, mediaData.copy(token = null))
        fakeExecutor.runAllReady()
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun loadWithNewKey() {
        // GIVEN that media data has been loaded with an old key
        manager.onMediaDataLoaded(KEY_OLD, null, mediaData)
        reset(listener)
        // WHEN data is loaded with a new key
        manager.onMediaDataLoaded(KEY, KEY_OLD, mediaData)
        // THEN the listener for the old key should removed.
        verify(lmm).unregisterCallback(any())
        // AND a new device event emitted
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun newKeySameAsOldKey() {
        // GIVEN that media data has been loaded
        manager.onMediaDataLoaded(KEY, null, mediaData)
        reset(listener)
        // WHEN the new key is the same as the old key
        manager.onMediaDataLoaded(KEY, KEY, mediaData)
        // THEN no event should be emitted
        verify(listener, never()).onMediaDeviceChanged(eq(KEY), any())
    }

    @Test
    fun unknownOldKey() {
        manager.onMediaDataLoaded(KEY, "unknown", mediaData)
        verify(listener).onMediaDeviceChanged(eq(KEY), any())
    }

    @Test
    fun updateToSessionTokenWithNullRoute() {
        // GIVEN that media data has been loaded with a null token
        manager.onMediaDataLoaded(KEY, null, mediaData.copy(token = null))
        // WHEN media data is loaded with a different token
        // AND that token results in a null route
        reset(listener)
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(null)
        manager.onMediaDataLoaded(KEY, null, mediaData)
        // THEN the device should be disabled
        fakeExecutor.runAllReady()
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
        assertThat(data.name).isNull()
        assertThat(data.icon).isNull()
    }

    @Test
    fun deviceEventOnAddNotification() {
        // WHEN a notification is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        val deviceCallback = captureCallback()
        // THEN the update is dispatched to the listener
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
        assertThat(data.icon).isEqualTo(icon)
    }

    @Test
    fun removeListener() {
        // WHEN a listener is removed
        manager.removeListener(listener)
        // THEN it doesn't receive device events
        manager.onMediaDataLoaded(KEY, null, mediaData)
        verify(listener, never()).onMediaDeviceChanged(eq(KEY), any())
    }

    @Test
    fun deviceListUpdate() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        val deviceCallback = captureCallback()
        // WHEN the device list changes
        deviceCallback.onDeviceListUpdate(mutableListOf(device))
        assertThat(fakeExecutor.runAllReady()).isEqualTo(1)
        // THEN the update is dispatched to the listener
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
        assertThat(data.icon).isEqualTo(icon)
    }

    @Test
    fun selectedDeviceStateChanged() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        val deviceCallback = captureCallback()
        // WHEN the selected device changes state
        deviceCallback.onSelectedDeviceStateChanged(device, 1)
        assertThat(fakeExecutor.runAllReady()).isEqualTo(1)
        // THEN the update is dispatched to the listener
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
        assertThat(data.icon).isEqualTo(icon)
    }

    @Test
    fun listenerReceivesKeyRemoved() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        // WHEN the notification is removed
        manager.onMediaDataRemoved(KEY)
        // THEN the listener receives key removed event
        verify(listener).onKeyRemoved(eq(KEY))
    }

    @Test
    fun deviceDisabledWhenMR2ReturnsNullRouteInfo() {
        // GIVEN that MR2Manager returns null for routing session
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(null)
        // WHEN a notification is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        // THEN the device is disabled
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
        assertThat(data.name).isNull()
        assertThat(data.icon).isNull()
    }

    @Test
    fun deviceDisabledWhenMR2ReturnsNullRouteInfoOnDeviceChanged() {
        // GIVEN a notif is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        reset(listener)
        // AND MR2Manager returns null for routing session
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(null)
        // WHEN the selected device changes state
        val deviceCallback = captureCallback()
        deviceCallback.onSelectedDeviceStateChanged(device, 1)
        fakeExecutor.runAllReady()
        // THEN the device is disabled
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
        assertThat(data.name).isNull()
        assertThat(data.icon).isNull()
    }

    @Test
    fun deviceDisabledWhenMR2ReturnsNullRouteInfoOnDeviceListUpdate() {
        // GIVEN a notif is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        reset(listener)
        // GIVEN that MR2Manager returns null for routing session
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(null)
        // WHEN the selected device changes state
        val deviceCallback = captureCallback()
        deviceCallback.onDeviceListUpdate(mutableListOf(device))
        fakeExecutor.runAllReady()
        // THEN the device is disabled
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
        assertThat(data.name).isNull()
        assertThat(data.icon).isNull()
    }

    fun captureCallback(): LocalMediaManager.DeviceCallback {
        val captor = ArgumentCaptor.forClass(LocalMediaManager.DeviceCallback::class.java)
        verify(lmm).registerCallback(captor.capture())
        return captor.getValue()
    }

    fun captureDeviceData(key: String): MediaDeviceData {
        val captor = ArgumentCaptor.forClass(MediaDeviceData::class.java)
        verify(listener).onMediaDeviceChanged(eq(key), captor.capture())
        return captor.getValue()
    }
}
