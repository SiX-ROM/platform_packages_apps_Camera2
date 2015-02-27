/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.camera.captureintent.state;

import com.google.common.base.Optional;

import com.android.camera.CaptureModuleUtil;
import com.android.camera.async.RefCountBase;
import com.android.camera.captureintent.event.EventOnStartPreviewFailed;
import com.android.camera.captureintent.event.EventOnStartPreviewSucceeded;
import com.android.camera.debug.Log;
import com.android.camera.exif.Rational;
import com.android.camera.one.OneCamera;
import com.android.camera.one.OneCameraAccessException;
import com.android.camera.one.OneCameraCharacteristics;
import com.android.camera.util.Size;

import java.util.List;

/**
 * Represents a state that the module is waiting for the preview video stream
 * to be started.
 */
public final class StateStartingPreview extends State {
    private static final Log.Tag TAG = new Log.Tag("StStartingPreview");

    private final RefCountBase<ResourceConstructed> mResourceConstructed;
    private final RefCountBase<ResourceSurfaceTexture> mResourceSurfaceTexture;
    private final RefCountBase<ResourceOpenedCamera> mResourceOpenedCamera;

    public static StateStartingPreview from(
            StateOpeningCamera openingCamera,
            RefCountBase<ResourceConstructed> resourceConstructed,
            RefCountBase<ResourceSurfaceTexture> resourceSurfaceTexture,
            OneCamera camera,
            OneCamera.Facing cameraFacing,
            OneCameraCharacteristics cameraCharacteristics,
            Size pictureSize) {
        return new StateStartingPreview(
                openingCamera,
                resourceConstructed,
                resourceSurfaceTexture,
                camera,
                cameraFacing,
                cameraCharacteristics,
                pictureSize);
    }

    private StateStartingPreview(
            State previousState,
            RefCountBase<ResourceConstructed> resourceConstructed,
            RefCountBase<ResourceSurfaceTexture> resourceSurfaceTexture,
            OneCamera camera,
            OneCamera.Facing cameraFacing,
            OneCameraCharacteristics cameraCharacteristics,
            Size pictureSize) {
        super(ID.StartingPreview, previousState);
        mResourceConstructed = resourceConstructed;
        mResourceConstructed.addRef();     // Will be balanced in onLeave().
        mResourceSurfaceTexture = resourceSurfaceTexture;
        mResourceSurfaceTexture.addRef();  // Will be balanced in onLeave().
        mResourceOpenedCamera = ResourceOpenedCamera.create(
                camera, cameraFacing, cameraCharacteristics, pictureSize);
    }

    @Override
    public Optional<State> onEnter() {
        final Size previewSize;
        try {
            // Pick a preview size with the right aspect ratio.
            final List<Size> supportedPreviewSizes = mResourceOpenedCamera.get()
                    .getCameraCharacteristics().getSupportedPreviewSizes();
            final Rational pictureAspectRatio =
                    mResourceConstructed.get().getResolutionSetting().getPictureAspectRatio(
                            mResourceOpenedCamera.get().getCameraFacing());
            previewSize = CaptureModuleUtil.getOptimalPreviewSize(
                    supportedPreviewSizes.toArray(new Size[(supportedPreviewSizes.size())]),
                    pictureAspectRatio.toDouble(),
                    null);
            if (previewSize == null) {
                return Optional.of((State) StateFatal.from(this, mResourceConstructed));
            }
        } catch (OneCameraAccessException ex) {
            return Optional.of((State) StateFatal.from(this, mResourceConstructed));
        }

        // Must do this before calling ResourceOpenedCamera.startPreview()
        // since SurfaceTexture.setDefaultBufferSize() needs to be called
        // before starting preview. Otherwise the size of preview video stream
        // will be wrong.
        mResourceSurfaceTexture.get().setPreviewSize(previewSize);

        // Start preview right away. Don't dispatch it on other threads or it
        // will cause race condition. b/19522251.
        mResourceOpenedCamera.get().startPreview(
                mResourceSurfaceTexture.get().createPreviewSurface(),
                mCaptureReadyCallback);
        return Optional.absent();
    }

    @Override
    public void onLeave() {
        mResourceConstructed.close();
        mResourceSurfaceTexture.close();
        mResourceOpenedCamera.close();
    }

    @Override
    public Optional<State> processPause() {
        return Optional.of((State) StateBackgroundWithSurfaceTexture.from(
                this, mResourceConstructed, mResourceSurfaceTexture));
    }

    @Override
    public final Optional<State> processOnTextureViewLayoutChanged(Size layoutSize) {
        mResourceSurfaceTexture.get().setPreviewLayoutSize(layoutSize);
        return NO_CHANGE;
    }

    @Override
    public Optional<State> processOnPreviewSetupSucceeded() {
        mResourceConstructed.get().getMainThread().execute(new Runnable() {
            @Override
            public void run() {
                mResourceConstructed.get().getModuleUI().onPreviewStarted();
            }
        });
        return Optional.of((State) StateReadyForCapture.from(
                this, mResourceConstructed, mResourceSurfaceTexture, mResourceOpenedCamera));
    }

    @Override
    public Optional<State> processOnPreviewSetupFailed() {
        Log.e(TAG, "processOnPreviewSetupFailed");
        return Optional.of((State) StateFatal.from(this, mResourceConstructed));
    }

    private OneCamera.CaptureReadyCallback mCaptureReadyCallback =
            new OneCamera.CaptureReadyCallback() {
                @Override
                public void onSetupFailed() {
                    getStateMachine().processEvent(new EventOnStartPreviewFailed());
                }

                @Override
                public void onReadyForCapture() {
                    getStateMachine().processEvent(new EventOnStartPreviewSucceeded());
                }
            };
}
