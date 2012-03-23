/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.camera;

import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.Camera.Parameters;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.android.camera.ui.PopupManager;
import com.android.camera.ui.RotateImageView;
import com.android.gallery3d.app.AbstractGalleryActivity;
import com.android.gallery3d.app.PhotoPage;
import com.android.gallery3d.app.GalleryActionBar;
import com.android.gallery3d.util.MediaSetUtils;

import java.io.File;

/**
 * Superclass of Camera and VideoCamera activities.
 */
abstract public class ActivityBase extends AbstractGalleryActivity
        implements CameraScreenNail.PositionChangedListener {

    private static final String TAG = "ActivityBase";
    private static boolean LOGV = false;
    private int mResultCodeForTesting;
    private boolean mOnResumePending;
    private Intent mResultDataForTesting;
    private OnScreenHint mStorageHint;
    private UpdateCameraAppView mUpdateCameraAppView = new UpdateCameraAppView();

    // The bitmap of the last captured picture thumbnail and the URI of the
    // original picture.
    protected Thumbnail mThumbnail;
    // An imageview showing showing the last captured picture thumbnail.
    protected RotateImageView mThumbnailView;
    protected int mThumbnailViewWidth; // layout width of the thumbnail
    protected AsyncTask<Void, Void, Thumbnail> mLoadThumbnailTask;
    protected boolean mOpenCameraFail;
    protected boolean mCameraDisabled;
    protected CameraManager.CameraProxy mCameraDevice;
    protected Parameters mParameters;
    protected boolean mPaused; // The activity is paused.
    protected GalleryActionBar mActionBar;

    // multiple cameras support
    protected int mNumberOfCameras;
    protected int mCameraId;

    protected CameraScreenNail mCameraScreenNail; // This shows camera preview.
    // The view containing only camera related widgets like control panel,
    // indicator bar, focus indicator and etc.
    protected View mCameraAppView;
    protected float mCameraAppViewAlpha = 1f;

    protected class CameraOpenThread extends Thread {
        @Override
        public void run() {
            try {
                mCameraDevice = Util.openCamera(ActivityBase.this, mCameraId);
                mParameters = mCameraDevice.getParameters();
            } catch (CameraHardwareException e) {
                mOpenCameraFail = true;
            } catch (CameraDisabledException e) {
                mCameraDisabled = true;
            }
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        if (Util.isTabletUI()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        requestWindowFeature(Window.FEATURE_ACTION_BAR);
        requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(icicle);
        // The full screen mode might be turned off previously. Add the flag again.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        mActionBar = new GalleryActionBar(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (LOGV) Log.v(TAG, "onWindowFocusChanged.hasFocus=" + hasFocus
                + ".mOnResumePending=" + mOnResumePending);
        if (hasFocus && mOnResumePending) {
            mPaused = false;
            doOnResume();
            mOnResumePending = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Don't grab the camera if in use by lockscreen. For example, face
        // unlock may be using the camera. Camera may be already opened in
        // onCreate. doOnResume should continue if mCameraDevice != null.
        // Suppose camera app is in the foreground. If users turn off and turn
        // on the screen very fast, camera app can still have the focus when the
        // lock screen shows up. The keyguard takes input focus, so the camera
        // app will lose focus when it is displayed.
        if (LOGV) Log.v(TAG, "onResume. hasWindowFocus()=" + hasWindowFocus());
        if (mCameraDevice == null && isKeyguardLocked()) {
            if (LOGV) Log.v(TAG, "onResume. mOnResumePending=true");
            mOnResumePending = true;
        } else {
            if (LOGV) Log.v(TAG, "onResume. mOnResumePending=false");
            mPaused = false;
            doOnResume();
            mOnResumePending = false;
        }
    }

    @Override
    protected void onPause() {
        mPaused = true;
        if (LOGV) Log.v(TAG, "onPause");
        saveThumbnailToFile();
        super.onPause();

        if (mLoadThumbnailTask != null) {
            mLoadThumbnailTask.cancel(true);
            mLoadThumbnailTask = null;
        }

        if (mStorageHint != null) {
            mStorageHint.cancel();
            mStorageHint = null;
        }

        mOnResumePending = false;
    }

    // Put the code of onResume in this method.
    abstract protected void doOnResume();

    @Override
    public boolean onSearchRequested() {
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Prevent software keyboard or voice search from showing up.
        if (keyCode == KeyEvent.KEYCODE_SEARCH
                || keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.isLongPress()) return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    protected void setResultEx(int resultCode) {
        mResultCodeForTesting = resultCode;
        setResult(resultCode);
    }

    protected void setResultEx(int resultCode, Intent data) {
        mResultCodeForTesting = resultCode;
        mResultDataForTesting = data;
        setResult(resultCode, data);
    }

    public int getResultCode() {
        return mResultCodeForTesting;
    }

    public Intent getResultData() {
        return mResultDataForTesting;
    }

    @Override
    protected void onDestroy() {
        PopupManager.removeInstance(this);
        super.onDestroy();
    }

    private boolean isKeyguardLocked() {
        KeyguardManager kgm = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (LOGV) {
            if (kgm != null) {
                Log.v(TAG, "kgm.isKeyguardLocked()="+kgm.isKeyguardLocked()
                        + ". kgm.isKeyguardSecure()="+kgm.isKeyguardSecure());
            }
        }
        // isKeyguardSecure excludes the slide lock case.
        return (kgm != null) && kgm.isKeyguardLocked() && kgm.isKeyguardSecure();
    }

    protected void updateStorageHint(long storageSpace) {
        String message = null;
        if (storageSpace == Storage.UNAVAILABLE) {
            message = getString(R.string.no_storage);
        } else if (storageSpace == Storage.PREPARING) {
            message = getString(R.string.preparing_sd);
        } else if (storageSpace == Storage.UNKNOWN_SIZE) {
            message = getString(R.string.access_sd_fail);
        } else if (storageSpace < Storage.LOW_STORAGE_THRESHOLD) {
            message = getString(R.string.spaceIsLow_content);
        }

        if (message != null) {
            if (mStorageHint == null) {
                mStorageHint = OnScreenHint.makeText(this, message);
            } else {
                mStorageHint.setText(message);
            }
            mStorageHint.show();
        } else if (mStorageHint != null) {
            mStorageHint.cancel();
            mStorageHint = null;
        }
    }

    private void updateThumbnailView() {
        if (mThumbnail != null) {
            mThumbnailView.setBitmap(mThumbnail.getBitmap());
            mThumbnailView.setVisibility(View.VISIBLE);
        } else {
            mThumbnailView.setBitmap(null);
            mThumbnailView.setVisibility(View.GONE);
        }
    }

    protected void getLastThumbnail() {
        mThumbnail = ThumbnailHolder.getLastThumbnail(getContentResolver());
        // Suppose users tap the thumbnail view, go to the gallery, delete the
        // image, and coming back to the camera. Thumbnail file will be invalid.
        // Since the new thumbnail will be loaded in another thread later, the
        // view should be set to gone to prevent from opening the invalid image.
        updateThumbnailView();
        if (mThumbnail == null) {
            mLoadThumbnailTask = new LoadThumbnailTask().execute();
        }
    }

    private class LoadThumbnailTask extends AsyncTask<Void, Void, Thumbnail> {
        @Override
        protected Thumbnail doInBackground(Void... params) {
            // Load the thumbnail from the file.
            ContentResolver resolver = getContentResolver();
            Thumbnail t = Thumbnail.getLastThumbnailFromFile(getFilesDir(), resolver);

            if (isCancelled()) return null;

            if (t == null) {
                // Load the thumbnail from the media provider.
                t = Thumbnail.getLastThumbnailFromContentResolver(resolver);
            }
            return t;
        }

        @Override
        protected void onPostExecute(Thumbnail thumbnail) {
            mThumbnail = thumbnail;
            updateThumbnailView();
        }
    }

    protected void gotoGallery() {
        Util.viewUri(mThumbnail.getUri(), this);
    }

    protected void saveThumbnailToFile() {
        if (mThumbnail != null && !mThumbnail.fromFile()) {
            new SaveThumbnailTask().execute(mThumbnail);
        }
    }

    private class SaveThumbnailTask extends AsyncTask<Thumbnail, Void, Void> {
        @Override
        protected Void doInBackground(Thumbnail... params) {
            final int n = params.length;
            final File filesDir = getFilesDir();
            for (int i = 0; i < n; i++) {
                params[i].saveLastThumbnailToFile(filesDir);
            }
            return null;
        }
    }

    // Call this after setContentView.
    protected void createCameraScreenNail(boolean getPictures) {
        mCameraAppView = findViewById(R.id.camera_app_root);
        Bundle data = new Bundle();
        String path = "/local/all/";
        // Intent mode does not show camera roll. Use 0 as a work around for
        // invalid bucket id.
        // TODO: add support of empty media set in gallery.
        path += (getPictures ? MediaSetUtils.CAMERA_BUCKET_ID : "0");
        data.putString(PhotoPage.KEY_MEDIA_SET_PATH, path);
        data.putString(PhotoPage.KEY_MEDIA_ITEM_PATH, path);

        // Send a CameraScreenNail to gallery to enable the camera preview.
        CameraScreenNailHolder holder = new CameraScreenNailHolder(this);
        data.putParcelable(PhotoPage.KEY_SCREENNAIL_HOLDER, holder);
        getStateManager().startState(PhotoPage.class, data);
        mCameraScreenNail = holder.getCameraScreenNail();
        mCameraScreenNail.setPositionChangedListener(this);
    }

    private class UpdateCameraAppView implements Runnable {
        @Override
        public void run() {
            if (mCameraAppViewAlpha == 0) {
                mCameraAppView.setVisibility(View.GONE);
            } else {
                mCameraAppView.setVisibility(View.VISIBLE);
                mCameraAppView.setAlpha(mCameraAppViewAlpha);
            }
        }
    }

    @Override
    public void onPositionChanged(int x, int y, boolean visible) {
        // Fade out the camera views with the swipe.
        if (!mPaused && !isFinishing()) {
            if (!visible) {
                mCameraAppViewAlpha = 0;
            } else {
                View v = (View) getGLRoot();
                mCameraAppViewAlpha = 1 + (float) x / v.getWidth() * 2;
            }
            if (mCameraAppViewAlpha < 0) mCameraAppViewAlpha = 0;
            runOnUiThread(mUpdateCameraAppView);
        }
    }

    @Override
    public GalleryActionBar getGalleryActionBar() {
        return mActionBar;
    }
}
