/*
 * Copyright (C) 2014 saki@serenegiant
 * Copyright (C) 2010 ZXing authors
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

package com.serenegiant.camera;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.serenegiant.widget.CameraView;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

@SuppressLint("InlinedApi")
public class CameraManager {
	private static final boolean DEBUG = false; // TODO set false when production
	private static final String TAG = DEBUG ? "CameraManager" : null;

	/**
	 * Minimum pixels on preview screen
	 */
	private static final int MIN_PREVIEW_PIXELS = 480 * 320;
	/**
	 * Maximum pixels on preview screen
	 * 
	 */
	private static final int MAX_PREVIEW_PIXELS = 960 * 720;
	/**
	 * maximum aspect difference limit when selecting preview size
	 */
	private static final double MAX_ASPECT_DISTORTION = 0.3;
	
	private static final String PARAMS_ROTATION = "rotation";
	// collection of focus-mode that can auto-focus
	private static final Collection<String> FOCUS_MODES_CALLING_AF;
	static {
		FOCUS_MODES_CALLING_AF = new ArrayList<String>(4);
		FOCUS_MODES_CALLING_AF.add(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
		FOCUS_MODES_CALLING_AF.add(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
		FOCUS_MODES_CALLING_AF.add(Camera.Parameters.FOCUS_MODE_AUTO);
		FOCUS_MODES_CALLING_AF.add(Camera.Parameters.FOCUS_MODE_MACRO);
	}
	// collection of focus-mode that the macro is available
	private static final Collection<String> FOCUS_MODES_CALLING_MACRO;
	static {
		FOCUS_MODES_CALLING_MACRO = new ArrayList<String>(2);
		FOCUS_MODES_CALLING_MACRO.add(Camera.Parameters.FOCUS_MODE_MACRO);
		FOCUS_MODES_CALLING_MACRO.add(Camera.Parameters.FOCUS_MODE_EDOF);
	}

	private final CameraView mView;
	private int mCameraID;
	// Camera instance
	private Camera mCamera;
	private Camera.Parameters mParams;
	// Camera thread for asynchronous camera access
	private CameraThread mCameraThread;
	private Point mViewSize;
	// preview size when the screen is landscape
	private Point mPreviewSize;
	private boolean mIsPortrite, mIsFrontFace;
	private boolean mCanMacroMode, mCanAutoFocus;
	private boolean mIsMacroMode, mIsAutoFocus, mIsMonoEffect;
	private int mRot_offset;						// rotation offset value to adjust preview rotation
	private boolean mIsZoomSupported;
	private boolean mIsSmoothZoomSupported;
	private boolean mInZooming;
	private int mZoom;								// current zoom scale
	private int mMaxZomm;							// maximum zoom scale
	public int mPreviewWidth, mPreviewHeight;		// preview size applied screen rotation

	public CameraManager(CameraView view) {
		mView = view;
	}
	
	public synchronized void OpenCamera(final int cameraID, final SurfaceHolder holder) {
		if (mCameraThread == null) {
			mCameraThread = new CameraThread();
			mCameraThread.start();
		}
		// request cametha thread to call camera open method
		// because that method may take a long time to complete on some devices.
		mCameraThread.queueEvent(new Runnable() {
			@Override
			public void run() {
				internalOpenCamera(cameraID, holder);
			}
		});
 	}

	private void internalOpenCamera(int cameraID, final SurfaceHolder holder) {
		if (DEBUG) Log.v(TAG, "OpenCamera:");
		if (mCamera == null) {	// camera is not opened yet
			mCameraID = cameraID;
			try {
				mCamera = Camera.open(cameraID);
				if (mCamera == null) {
					throw new IOException();
				}
				mCamera.setPreviewDisplay(holder);
				mPrevDegrees = -1;
			} catch (Exception e) {
				Log.w(TAG, e);
			}
		}	
	}
	
	/**
	 * release camera
	 */
	public synchronized void closeCamera() {
		if (DEBUG) Log.v(TAG, "closeCamera:");
		mParams = null;
		mViewSize = null;
		if (mCamera != null) {
			mCamera.stopPreview();
			mCamera.release();
			mCamera = null;
		}
		if (mCameraThread != null) {
			mCameraThread.terminate();
			mCameraThread = null;
		}
	}
	
	public void setFocusMode(final boolean macroMode, final boolean autoFocus, final boolean monoEffect) {
		mIsMacroMode = macroMode;
		mIsAutoFocus = autoFocus;
		mIsMonoEffect = monoEffect;
	}
	
	/**
	 * setup camera parameters
	 * @param viewWidth
	 * @param viewHeight
	 * @param macroMode
	 * @param autofocus
	 * @param monoEffect
	 * @param autoFocusCallback
	 */
	public synchronized void setupCameraParams(final int viewWidth, final int viewHeight,
		final AutoFocusCallback autoFocusCallback) {
		
		// request camera thread to call setup method to guarantee
		// that setup method is called after camera opened. 
		mCameraThread.queueEvent(new Runnable() {
			@Override
			public void run() {
				internalSetupCameraParams(viewWidth, viewHeight);
				autoFocus(autoFocusCallback);
				mView.startRead(true);
			}			
		});
	}

	/**
	 * internal method to setup cammera parameters
	 * @param viewWidth
	 * @param viewHeight
	 */
	private final void internalSetupCameraParams(int viewWidth, int viewHeight) {
		
		if (DEBUG) Log.v(TAG, String.format("setupCameraParams:viewWidth=%d,viewHeight=%d",
			viewWidth, viewHeight));
		
		// if camera is not ready yet, return immediately
		if (mCamera == null) return;
        mCamera.stopPreview();
		mParams = getCameraParams();
		// check image format
		// if the image format is other than NV21/YV12/YUY2, change to NV21
		final int previewFormat = mParams.getPreviewFormat();
		if ((previewFormat != ImageFormat.NV21)
			&& (previewFormat != ImageFormat.YV12)
			&& (previewFormat != ImageFormat.YUY2)) {
			final List<Integer>supportedPreviewFormats = mParams.getSupportedPreviewFormats();
			if ((supportedPreviewFormats != null) && supportedPreviewFormats.contains(Integer.valueOf(ImageFormat.NV21))) { 
				mParams.setPreviewFormat(ImageFormat.NV21);
			} else {
				if (supportedPreviewFormats != null)
					Log.w(TAG, String.format("could not set previewFormat to NV21:=%d,supported=", previewFormat)
							+ supportedPreviewFormats);
			}
		}
		final Display display = ((WindowManager)mView.getContext()
				.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
		final DisplayMetrics metrics = new DisplayMetrics();
		display.getMetrics(metrics);
		mIsPortrite = (metrics.widthPixels < metrics.heightPixels);
		// rotate preview screen
		setRotation(mParams, display.getRotation());
		mViewSize = new Point(viewWidth, viewHeight);
		mPreviewSize = findBestPreviewSizeValue(mParams, viewWidth, viewHeight);
		if (DEBUG) Log.v(TAG, "setPreviewSize: " + mPreviewSize);
	    mParams.setPreviewSize(mPreviewSize.x, mPreviewSize.y);
	    selectFocusMode(mParams, mIsMacroMode, mIsAutoFocus);
	    // set camera effect
		if (mIsMonoEffect)
			setColorEffect(mParams, Camera.Parameters.EFFECT_MONO);
		// apply camera parameters
	    mCamera.setParameters(mParams);
	    // confirm camera parameters
	    updateCameraFlag();
		mCamera.startPreview();
	}

	public synchronized final void zoomIn() {
		mZoom++;
		if (mZoom > mMaxZomm) mZoom = mMaxZomm;
		setZomm(mZoom);
	}
	
	public synchronized final void zoomOut() {
		mZoom--;
		if (mZoom < 0) mZoom = 0;
		setZomm(mZoom);
	}

	private final void setZomm(int zoom) {
		if (DEBUG) Log.v(TAG,  "SetZoom:zoom=" + zoom);
		
		if (mCamera != null && (mZoom != zoom)) {
			if (mIsSmoothZoomSupported && !mInZooming) {
				mInZooming = true;
				mCamera.setZoomChangeListener(mOnZoomChangeListener);
				mCamera.startSmoothZoom(mMaxZomm);
			} else if (mIsZoomSupported) {
				final Camera.Parameters params = mCamera.getParameters();
				params.setZoom(zoom);
				mCamera.setParameters(params);
			}
		}
	}
	
	/**
	 * callback for zoom changes during a smooth zoom operation.
	 */
	private final Camera.OnZoomChangeListener mOnZoomChangeListener = new Camera.OnZoomChangeListener() {

		@Override
		public void onZoomChange(int zoomValue, boolean stopped, Camera camera) {
			mInZooming = !stopped;
			if (zoomValue > mZoom) {
				mZoom = zoomValue;
				camera.stopSmoothZoom();
			}
		}
		
	};

	public synchronized Camera getCamera() {
		return mCamera;
	}
	
	public synchronized boolean isActive() {
		return (mCamera != null) && (mViewSize != null);
	}
	
	public synchronized Camera.Parameters getCameraParams() {
		if (mCamera != null) {
			mParams = mCamera.getParameters();
		} else {
			mParams = null;
		}
		return mParams;
	}
	
	public Point getPreviewSize() {
	    return mPreviewSize;
	}

	public Point getViewSize() {
		return mViewSize;
	}
	
	public boolean isPortrite() {
		return mIsPortrite;
	}

	public boolean isFrontFace() {
		return mIsFrontFace;
	}
	
	public boolean canAutoFocus() {
		return mCanAutoFocus;
	}

	/**
	 * set offset value of camera rotation.</br>
	 * this method should be called before #setupCameraParams called
	 * @param offset
	 */
	public void setRotationOffset(int offset) {
		mRot_offset = offset;
	}
	
	public int getRotationOffset() {
		return mRot_offset;
	}

	/**
	 * get whether the macro-mode is available
	 * @return
	 */
	public boolean canMacroMode() {
		return mCanMacroMode;
	}
	
	/**
	 * get whether current focus-mode is macro-mode
	 * @return
	 */
	public boolean isMacroMode() {
		return mIsMacroMode && mCanMacroMode;
	}
	
	/**
	 * set macro-mode on/off (this value is ignored when macro-mode is not available)
	 * @param isMacroMode
	 * @return whether current focus-mode is macro-mode
	 */
	public synchronized boolean setMacroMode(boolean isMacroMode) {
		boolean b = false;
		if ((mCamera != null) && mCanMacroMode) {
			final Camera.Parameters params = getCameraParams();
			mCamera.stopPreview();
			b = selectFocusMode(params, isMacroMode, mIsAutoFocus);
			mCamera.setParameters(params);
			updateCameraFlag();
			mCamera.startPreview();
		}
		mIsMacroMode = isMacroMode;
		return b & isMacroMode;
	}
	
	/**
	 * start auto-focus
	 * @param callback
	 */
	public synchronized void autoFocus(AutoFocusCallback callback) {
		if ((mCamera != null) && (mCameraThread != null) && mCanAutoFocus) {
			mCamera.autoFocus(callback);
		}
	}

	public void queueEvent(Runnable event, long delayMillis) {
		mCameraThread.queueEvent(event, delayMillis);
	}
	
	public void queueEvent(Runnable event) {
		mCameraThread.queueEvent(event);
	}

	public void removeEvent(Runnable event) {
		mCameraThread.removeEvent(event);
	}
	
	/**
	 * get optimum preview size fit to the current view size 
	 * @param parameters
	 * @param width viewの幅
	 * @param height viewの高さ
	 * @return
	 */
	private final Point findBestPreviewSizeValue(Camera.Parameters parameters, int viewWidth, int viewHeight) {
		if (DEBUG) Log.v(TAG, String.format("findBestPreviewSizeValue:width=%d,height=%d", viewWidth, viewHeight));
		// get view size when device will be in landscape.
		final int width = mIsPortrite ? viewHeight : viewWidth;
		final int height = mIsPortrite ? viewWidth : viewHeight;
		if (DEBUG) Log.v(TAG, String.format("findBestPreviewSizeValue:landscape size=(%d,%d)", width, height));

		final List<Camera.Size> rawSupportedSizes = parameters.getSupportedPreviewSizes();
		if (rawSupportedSizes == null) {
			if (DEBUG) Log.w(TAG, "Device returned no supported preview sizes; using default");
			final Camera.Size defaultSize = parameters.getPreviewSize();
			return new Point(defaultSize.width, defaultSize.height);
		}
	    // Sort by size, descending
		final List<Camera.Size> supportedPreviewSizes = new ArrayList<Camera.Size>(rawSupportedSizes);
		Collections.sort(supportedPreviewSizes, new Comparator<Camera.Size>() {
			@Override
			public int compare(Camera.Size a, Camera.Size b) {
				int aPixels = a.height * a.width;
				int bPixels = b.height * b.width;
				if (bPixels < aPixels) {
					return -1;
				}
				if (bPixels > aPixels) {
					return 1;
				}
				return 0;
			}
		});

		final double screenAspectRatio = width / (double) height;

	    // Remove sizes that are unsuitable
		final Iterator<Camera.Size> it = supportedPreviewSizes.iterator();
		Camera.Size supportedPreviewSize;
		int realWidth, realHeight, realPixels;
		boolean isCandidatePortrait;
		int maybeFlippedWidth, maybeFlippedHeight;
		double aspectRatio, distortion;
		
		while (it.hasNext()) {
			supportedPreviewSize = it.next();
			realWidth = supportedPreviewSize.width;
			realHeight = supportedPreviewSize.height;
			realPixels = realWidth * realHeight;
			
			if (realPixels < MIN_PREVIEW_PIXELS || realPixels > MAX_PREVIEW_PIXELS) {
				if (DEBUG) Log.i(TAG, String.format("skipped by PIXEL LIMIT(%dx%d)=%d", realWidth, realHeight, realPixels));
				it.remove();
				continue;
			}

			isCandidatePortrait = realWidth < realHeight;
			maybeFlippedWidth = isCandidatePortrait ? realHeight : realWidth;
			maybeFlippedHeight = isCandidatePortrait ? realWidth : realHeight;
			aspectRatio = (double) maybeFlippedWidth / (double) maybeFlippedHeight;
			distortion = Math.abs(aspectRatio - screenAspectRatio);
			if (distortion > MAX_ASPECT_DISTORTION) {
				if (DEBUG) Log.i(TAG, String.format("skipped by MAX_ASPECT_DISTORTION screen=%f,aspect=%f,distortion=%f",
					screenAspectRatio, aspectRatio, distortion));
				it.remove();
				continue;
			}

			if ((maybeFlippedWidth == width) && (maybeFlippedHeight == height)) {
				// the size fit perfectly
				final Point exactPoint = new Point(realWidth, realHeight);
				if (DEBUG) Log.i(TAG, "Found preview size exactly matching screen size: " + exactPoint);
				return exactPoint;
			}
		}

		// If no exact match, use largest preview size. This was not a great
		// idea on older devices because of the additional computation needed.
		// We're likely to get here on newer Android 4+ devices,
		// where the CPU is much more powerful.
		if (!supportedPreviewSizes.isEmpty()) {
			final Camera.Size largestPreview = supportedPreviewSizes.get(0);
			final Point largestSize = new Point(largestPreview.width, largestPreview.height);
			if (DEBUG) Log.i(TAG, "Using largest suitable preview size: " + largestSize);
			return largestSize;
		}

		// If there is nothing at all suitable, return current preview size
		final Camera.Size defaultPreview = parameters.getPreviewSize();
		final Point defaultSize = new Point(defaultPreview.width, defaultPreview.height);
		if (DEBUG) Log.i(TAG, "No suitable preview sizes, using default: " + defaultSize);
		return defaultSize;
	}
	
	private int mPrevDegrees;
	/**
	 * set rotation of preview
	 * @param rotation: the value from Display#getRotation
	 */
	private final void setRotation(Camera.Parameters params, int rotation) {
		final Camera.CameraInfo info =
			new android.hardware.Camera.CameraInfo();
		android.hardware.Camera.getCameraInfo(mCameraID, info);
		int degrees = 0;
		switch (rotation) {
			case Surface.ROTATION_0: degrees = 0; break;
			case Surface.ROTATION_90: degrees = 90; break;
			case Surface.ROTATION_180: degrees = 180; break;
			case Surface.ROTATION_270: degrees = 270; break;
		}
		boolean flag = false;
		try {
			flag = params.getInt(PARAMS_ROTATION) == mPrevDegrees;
		} catch (Exception e) {
		}
		// get whether the camera is front camera
		mIsFrontFace = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
		if (mIsFrontFace) {	// front camera
			degrees = (info.orientation + (flag ? mRot_offset : 0) + degrees) % 360;
			degrees = (360 - degrees) % 360;  // compensate the mirror
		} else {  // back camera
			degrees = (info.orientation + (flag ? mRot_offset : 0) - degrees + 360) % 360;
		}
		// set rotation
		mCamera.setDisplayOrientation(degrees);
		params.setRotation(degrees);
		params.set(PARAMS_ROTATION, degrees);
		mPrevDegrees = degrees;
		if (DEBUG) Log.v(TAG, String.format("setRotation:isFrontFace=%d,orientation=%d, rotation=%d,degrees=%d",
			mIsFrontFace ? 1 : 0, info.orientation, rotation, degrees));
		if (DEBUG) Log.v(TAG, String.format("setRotation:params_rotation=%d", params.getInt(PARAMS_ROTATION)));
	}

	/**
	 * set color effect
	 * @param params
	 * @param effect (Camera.Parameters.EFFECT_XX)
	 * @return return true if the requested value can be set correctly
	 */
	private final boolean setColorEffect(Camera.Parameters params, String effect) {
		boolean result = false;
		final List<String> colorEffects = params.getSupportedColorEffects();
		if (colorEffects != null) {
			for (String item: colorEffects) {
				if (item.equals(effect)) {
					if (DEBUG) Log.v(TAG, "setColorEffect:" + effect);
					params.setColorEffect(effect);
					result = true;
					break;
				}
			}
		}
		return result;
	}
	
	/**
	 * set focus-mode(macro-mode is given priority over other mode)
	 * @param macroMode
	 * @param autofocus
	 * @return return true if the requested value can be set correctly
	 */
	private final boolean selectFocusMode(Camera.Parameters params, boolean macroMode, boolean autofocus) {
		String focusMode = null;
		mIsAutoFocus = autofocus;
		boolean result = false;
		// if macroMode is true, try to set macro-mode preferentially
	    if (macroMode) {
	    	focusMode = findSettableValue(params.getSupportedFocusModes(), FOCUS_MODES_CALLING_MACRO);
	    }
	    // if macroMode is false ot macro-mode is not available and macroMode is true
	    // try to set auto-focus mode
	    if (autofocus && (focusMode == null)) {
	    	focusMode = findSettableValue(params.getSupportedFocusModes(), FOCUS_MODES_CALLING_AF);
	    }
	    if (focusMode != null) {
	    	result = setFocusMode(params, focusMode);
	    }
	    return result;
    }

	/**
	 * set focus-mode
	 * @param params
	 * @param mode (Camera.Parameters.FOCUS_MODE_XX)
	 * @return return true if the requested value can be set correctly
	 */
	private final boolean setFocusMode(Camera.Parameters params, String mode) {
		boolean result = false;
		final List<String> focuses = params.getSupportedFocusModes();
		if (focuses != null) {
			for (String item: focuses) {
				if (item.equals(mode)) {
					if (DEBUG) Log.v(TAG, "SetFocusMode:" + mode);
					params.setFocusMode(mode);
					result = true;
					break;
				}
			}
		}
		return result;
	}

	/**
	 * select an available value from the list
	 * @param supportedValues
	 * @param desiredValues
	 * @return
	 */
	private static final String findSettableValue(Collection<String> supportedValues,
		Collection<String> desiredValues) {
		
		if (DEBUG) Log.i(TAG, "Supported values: " + supportedValues);
		String result = null;
		if (supportedValues != null) {
			for (String desiredValue : desiredValues) {
				if (supportedValues.contains(desiredValue)) {
					result = desiredValue;
					break;
				}
			}
		}
		if (DEBUG) Log.i(TAG, "Settable value: " + result);
		return result;
	}

	/**
	 * confirm the camera parameters
	 */
    private final void updateCameraFlag() {
	    final Camera.Parameters params = mCamera.getParameters();
	    final String focusMode = params.getFocusMode();
	    // whether macro-mode is available
	    mCanMacroMode = FOCUS_MODES_CALLING_MACRO.contains(focusMode);
	    // whether auto-focus is available
	    mCanAutoFocus = FOCUS_MODES_CALLING_AF.contains(focusMode);
		// whether zooming is available
		mIsZoomSupported = params.isZoomSupported();
		// whether smooth zooming is available
		mIsSmoothZoomSupported = params.isSmoothZoomSupported();
		if (mIsZoomSupported) {
			// get current zoom scale
			mZoom = params.getZoom();
			// get maximum zoom scale
			mMaxZomm = params.getMaxZoom();
		} else {
			mZoom = mMaxZomm = 0;
		}
	    final Camera.Size previewSize = params.getPreviewSize();
	    if (previewSize!= null && (
	    	(mPreviewSize.x != previewSize.width) || (mPreviewSize.y != previewSize.height)) ) {
	    	if (DEBUG) Log.w(TAG, "Camera said it supported preview size " + mPreviewSize.x + 'x' + mPreviewSize.y +
	                 ", but after setting it, preview size is " + previewSize.width + 'x' + previewSize.height);
	    	mPreviewSize.x = previewSize.width;
	    	mPreviewSize.y = previewSize.height;
	    }
		// rotate preview size to adjust actual screen orientation
		mPreviewWidth = mIsPortrite ? mPreviewSize.y : mPreviewSize.x;
		mPreviewHeight = mIsPortrite ? mPreviewSize.x : mPreviewSize.y;
    }
    
	/**
	 * Camera thread
	 */
	private static final class CameraThread extends Thread {
		private Handler mHandler;
		private CountDownLatch mHandlerInitLatch;
		
		public CameraThread() {
			mHandlerInitLatch = new CountDownLatch(1);
		}
		
		@Override
		public void run() {
			Looper.prepare();
			mHandler = new Handler();
			// release latch
			mHandlerInitLatch.countDown();
			Looper.loop();
		    if (DEBUG) Log.v("CameraThread", "finished");
		}
		
		public Handler getHandler() {
			try {
				mHandlerInitLatch.await();
			} catch (InterruptedException ie) {
			}
			return mHandler;
		}
		
		public void queueEvent(Runnable event) {
			if (event == null) {
				throw new NullPointerException("Runnable must not be null");
			}
			final Handler handler = getHandler();
			if (handler != null)
				handler.post(event);
		}

		public void queueEvent(Runnable event, long delayMillis) {
			if (event == null) {
				throw new NullPointerException("Runnable must not be null");
			}
			final Handler handler = getHandler();
			if (handler != null)
				mHandler.postDelayed(event, delayMillis);
		}

		public void removeEvent(Runnable event) {
			if (event == null) {
				throw new NullPointerException("Runnable must not be null");
			}
			final Handler handler = getHandler();
			if (handler != null)
				handler.removeCallbacks(event);
		}
		
		public void terminate() {
			if (mHandlerInitLatch != null) {
				if (DEBUG) Log.v(TAG, "CameraThread:terminate");
				final Handler handler = getHandler();
				if ((handler != null) && isAlive()) {
					final Looper looper = mHandler.getLooper();
					looper.quit();
				}
				if (isAlive()) {
					try {
						join(500L);
					} catch (InterruptedException e) {
					}
				}
				if (DEBUG) Log.v(TAG, "CameraThread:terminated");
			}
			mHandlerInitLatch = null;
			mHandler = null;
		}
		
		@Override
		protected void finalize() throws Throwable {
			terminate();
			super.finalize();
		}

	}

}
