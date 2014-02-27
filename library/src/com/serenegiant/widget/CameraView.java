/*
 * Copyright (C) 2014 saki@serenegiant
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

package com.serenegiant.widget;


import java.io.IOException;

import com.serenegiant.camera.CameraManager;
import com.serenegiant.cameralib.R;

import android.content.Context;
import android.content.res.TypedArray;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

public class CameraView extends FrameLayout
	implements Camera.PreviewCallback, AutoFocusCallback {

	private static final boolean DEBUG = true;	// TODO set to false when production
	protected final String TAG = DEBUG ? getClass().getSimpleName() : null;

	protected static final String PARAMS_ROTATION = "rotation";
	
	/**
	 * camera id
	 */
	private int mCameraID;
	/**
	 * SufaceView instance for preview
	 */
	private final SurfaceView mSurfaceView;
	/**
	 * reference to the camera manager instance
	 */
	private CameraManager mCameraManager;
	/**
	 * flag whether surface exist or nor</br>
	 * set true in #surfaceCreated and clear in #surfaceDestroyed</br>
	 */
	private boolean mSurfaceExist;
		
	@SuppressWarnings("deprecation")
	public CameraView(Context context, AttributeSet attrs) {
		super(context, attrs);
		// get parameters from xml layout resource
        TypedArray attributesArray = context.obtainStyledAttributes(attrs, R.styleable.CameraView);
        mCameraID = attributesArray.getInt(R.styleable.CameraView_camera_id, 0);
		final boolean isMacroMode = attributesArray.getBoolean(R.styleable.CameraView_focus_mode_macro, false);
		final boolean isEffectMono = attributesArray.getBoolean(R.styleable.CameraView_effect_mono, false);
		final int rot_offset = attributesArray.getInt(R.styleable.CameraView_rotation_offset, 0);
		attributesArray.recycle();
		attributesArray = null;
		
		mCameraManager = getCameraManager();
		mCameraManager.setFocusMode(isMacroMode, true, isEffectMono);
		mCameraManager.setRotationOffset(rot_offset);
		// create SurfaceView
		mSurfaceView = new SurfaceView(context);
		final SurfaceHolder holder = mSurfaceView.getHolder();
		// set SurfaceView type to pushbuffers
		// stock operations of GPU in the main memory and execute then at one time
		// this value is set automatically when needed after API11,
		// but some devices need this value on previous API level.
		holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		holder.addCallback(mSurfaceHolderCallback);
		addView(mSurfaceView);
	}

	@Override
	public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		if (DEBUG) Log.v(TAG, "onWindowFocusChanged:hasWindowFocus=" + hasWindowFocus);
		if (hasWindowFocus) {
			resume();
		} else {
			pause();
		}
	}

	/**
	 * start/restart CameraView
	 */
	public synchronized void resume() {
		if (DEBUG) Log.v(TAG, "resume:");
		if (mSurfaceExist) {	// surface is exists while activity was already paused & stoped
			resumeCamera();
			setupCameraParams();
		}
	}

	/**
	 * release camera & camera thread
	 */
	public synchronized void pause() {
		if (DEBUG) Log.v(TAG, "pause:");
		if (mCameraManager != null) {
			mCameraManager.removeEvent(mAutoFocusRunnable);
			mCameraManager.closeCamera();
		}
	}

	/**
	 * callbacks for SufaceView (these methods are called from UI thread)
	 */
	private final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
		@Override
		public final void surfaceCreated(final SurfaceHolder holder) {
			if (DEBUG) Log.v(TAG, "surfaceCreated");
			mSurfaceExist = true;
			resumeCamera();
		}

		@Override
		public final void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
			if (DEBUG) Log.v(TAG, "surfaceChanged");
			// when surface changed
        	setupCameraParams();	
		}

		@Override
		public final void surfaceDestroyed(SurfaceHolder holder) {
			if (DEBUG) Log.v(TAG, "surfaceDestroyed");
			// when destroied surface
			mSurfaceExist = false;
			pause();
		}
	};

	/**
	 * callback method when finished preview</br>
	 * This method do nothing in CameraView. You can override this if you need.
	 */
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
	}
	
	/**
	 * callback method when auto-focus finished
	 */
	@Override
	public void onAutoFocus(boolean success, Camera camera) {
		if (DEBUG) Log.v(TAG, "onAutoFocus:success=" + success);
		// if aoto-focus successed, request next after 2 seconds.
		// if failed, request next after 200 milliseconds
		mCameraManager.queueEvent(mAutoFocusRunnable, success ? 2000 : 200);
	}

	/**
	 * Runnable for auto-focusing
	 */
	private final Runnable mAutoFocusRunnable = new Runnable() {
		@Override
		public void run() {
			if (DEBUG) Log.v(TAG, "autoFocus:");
			mCameraManager.autoFocus(CameraView.this);
		}		
	};

	/**
	 * open camera and start previewing</br>
	 * this method only request to camera thread
	 * @param holder
	 * @throws IOException
	 */
	protected synchronized void openCamera(final SurfaceHolder holder) {
		if (DEBUG) Log.v(TAG, "openCamera:");
		mCameraManager.OpenCamera(mCameraID,  holder);
	}

	
	/**
	 * setup camera parameters like preview size, etc.</br>
	 * this method only request to camera thread
	 */
	protected synchronized final void setupCameraParams() {
		if (DEBUG) Log.v(TAG, "setupCameraParams:");
		mCameraManager.setupCameraParams(getWidth(), getHeight(), this);
	}

	/**
	 * start/restart camera view(when creating this view and resume from pause)</br>
	 * Do nothing when surface does not exist
	 */
	protected synchronized void resumeCamera() {
		if (DEBUG) Log.v(TAG, "resumeCamera:");
		if (mSurfaceExist) {
			openCamera(mSurfaceView.getHolder());
		}
	}

	/**
	 * Change camera
	 * @param camera_id
	 */
	public synchronized final void setCameraID(int camera_id) {
		if (mCameraID != camera_id) {	// select different camera?
			// try to close current selected camera
			mCameraManager.closeCamera();
			mCameraID = camera_id;
			resumeCamera();
			setupCameraParams();
		}
	}

	public final int getCameraID() {
		return mCameraID;
	}

	/**
	 * create CameraManager instance when it is not created yet.</br>
	 * this method is called from #getCameraManager if necessary.
	 * if you need inherited　CameraManager instance, you can override this method
	 * @return
	 */
	protected CameraManager createCameraManager() {
		return new CameraManager(this);
	}
	
	/**
	 * return CameraManager
	 * @return
	 */
	public final CameraManager getCameraManager() {
		if (DEBUG) Log.v(TAG, "getCameraManager:");
		if (mCameraManager == null)
			mCameraManager = createCameraManager();
		return mCameraManager;
	}

	/**
	 * set offset of screen rotation
	 * Some devices never return collect rotation value and automatic screen rotation fail.
	 * You can use this method to fixed that rotation failure.
	 * @param offset
	 */
	public void setRotationOffset(int offset) {
		mCameraManager.setRotationOffset(offset);
	}
	
	/**
	 * get current offset of screen rotation
	 * @return
	 */
	public int getRotationOffset() {
		return mCameraManager.getRotationOffset();
	}
	
	/**
	 * get whether the screen is portrite
	 * @return
	 */
	public final boolean isPortrite() {
		return mCameraManager.isPortrite();
	}
	
	/**
	 * get whether the camera is macro-mode
	 * @return
	 */
	public boolean isMacroMode() {
		return mCameraManager.isMacroMode();
	}
	
	/**
	 * set macro-mode of camera on/off
	 * @param isMacroMode
	 */
	public void setMacroMode(boolean isMacroMode) {
		mCameraManager.setMacroMode(isMacroMode);
	}
	
	/**
	 * request to start preview, synonym of requestPreviewFrame in this class
	 * @param force
	 */
	public void startRead(boolean force) {
		requestPreviewFrame();
	}

	/**
	 * request to start preview onece
	 */
	public synchronized void requestPreviewFrame() {
		if (DEBUG) Log.v(TAG, "requestPreviewFrame");
		final Camera camera = mCameraManager.getCamera();
		if (camera != null) {
			camera.setOneShotPreviewCallback(this);
		}
	}

}
