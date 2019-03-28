/*
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
#pragma once

#include <system/graphics.h>
#include <system/window.h>
#include <vulkan/vulkan.h>

#include <SkSize.h>
#include <SkRefCnt.h>

#include "IRenderPipeline.h"

class SkSurface;

namespace android {
namespace uirenderer {
namespace renderthread {

class VulkanManager;

class VulkanSurface {
public:
    static VulkanSurface* Create(ANativeWindow* window,
                                  ColorMode colorMode,
                                  SkColorType colorType,
                                  sk_sp<SkColorSpace> colorSpace,
                                  GrContext* grContext,
                                  const VulkanManager& vkManager);
    ~VulkanSurface();

    sk_sp<SkSurface> getCurrentSkSurface() {
        return mCurrentBufferInfo ? mCurrentBufferInfo->skSurface : nullptr;
    }
    const SkMatrix& getCurrentPreTransform() { return mWindowInfo.preTransform; }

private:
    /*
     * All structs/methods in this private section are specifically for use by the VulkanManager
     *
     */
    friend VulkanManager;
    struct NativeBufferInfo {
        sk_sp<SkSurface> skSurface;
        sp<ANativeWindowBuffer> buffer;
        // The fence is only valid when the buffer is dequeued, and should be
        // -1 any other time. When valid, we own the fd, and must ensure it is
        // closed: either by closing it explicitly when queueing the buffer,
        // or by passing ownership e.g. to ANativeWindow::cancelBuffer().
        int dequeue_fence = -1;
        bool dequeued = false;
        uint32_t lastPresentedCount = 0;
        bool hasValidContents = false;
    };

    NativeBufferInfo* dequeueNativeBuffer();
    NativeBufferInfo* getCurrentBufferInfo() { return mCurrentBufferInfo; }
    bool presentCurrentBuffer(const SkRect& dirtyRect, int semaphoreFd);

    // The width and height are are the logical width and height for when submitting draws to the
    // surface. In reality if the window is rotated the underlying window may have the width and
    // height swapped.
    int logicalWidth() const { return mWindowInfo.size.width(); }
    int logicalHeight() const { return mWindowInfo.size.height(); }
    int getCurrentBuffersAge();

private:
    /*
     * All code below this line while logically available to VulkanManager should not be treated
     * as private to this class.
     *
     */

    // How many buffers we want to be able to use ourselves. We want 1 in active rendering with
    // 1 more queued, so 2. This will be added to NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, which is
    // how many buffers the consumer needs (eg, 1 for SurfaceFlinger), getting to a typically
    // triple-buffered queue as a result.
    static constexpr uint32_t sTargetBufferCount = 2;

    struct WindowInfo {
        SkISize size;
        PixelFormat pixelFormat;
        android_dataspace dataspace;
        int transform;
        size_t bufferCount;
        uint64_t windowUsageFlags;

        // size of the ANativeWindow if the inverse of transform requires us to swap width/height
        SkISize actualSize;
        // transform to be applied to the SkSurface to map the coordinates to the provided transform
        SkMatrix preTransform;
    };

    VulkanSurface(ANativeWindow* window,
                  const WindowInfo& windowInfo,
                  SkISize minWindowSize,
                  SkISize maxWindowSize,
                  GrContext* grContext);
    static bool UpdateWindow(ANativeWindow* window,
                             const WindowInfo& windowInfo);
    static void ComputeWindowSizeAndTransform(WindowInfo* windowInfo,
                                              const SkISize& minSize,
                                              const SkISize& maxSize);
    void releaseBuffers();

    // TODO: Just use a vector?
    NativeBufferInfo mNativeBuffers[android::BufferQueueDefs::NUM_BUFFER_SLOTS];

    sp<ANativeWindow> mNativeWindow;
    WindowInfo mWindowInfo;
    GrContext* mGrContext;

    uint32_t mPresentCount = 0;
    NativeBufferInfo* mCurrentBufferInfo = nullptr;

    const SkISize mMinWindowSize;
    const SkISize mMaxWindowSize;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */