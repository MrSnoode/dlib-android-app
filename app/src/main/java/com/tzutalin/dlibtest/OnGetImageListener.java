/*
 * Copyright 2016-present Tzutalin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tzutalin.dlibtest;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.tzutalin.dlib.Constants;
import com.tzutalin.dlib.FaceDet;
import com.tzutalin.dlib.VisionDetRet;

import junit.framework.Assert;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class that takes in preview frames and converts the image to Bitmaps to process with dlib lib.
 */
public class OnGetImageListener implements OnImageAvailableListener {
    private static final boolean SAVE_PREVIEW_BITMAP = false;

    private static final String TAG = "OnGetImageListener";

    private int mScreenRotation = 90;

    private int mPreviewWdith = 0;
    private int mPreviewHeight = 0;
    private byte[][] mYUVBytes;
    private int[] mRGBBytes = null;
    private Bitmap mRGBframeBitmap = null;
    private Bitmap mCroppedBitmap = null;

    private boolean mIsComputing = false;
    private Handler mInferenceHandler;

    private Context mContext;
    private FaceDet mFaceDet;
    private FloatingCameraWindow mWindow;
    private Paint mFaceLandmardkPaint;

    public void initialize(
            final Context context,
            final AssetManager assetManager,
            final TrasparentTitleView scoreView,
            final Handler handler) {
        this.mContext = context;
        this.mInferenceHandler = handler;
        mFaceDet = new FaceDet(Constants.getFaceShapeModelPath());
        mWindow = new FloatingCameraWindow(mContext);

        mFaceLandmardkPaint = new Paint();
        mFaceLandmardkPaint.setColor(Color.GREEN);
        mFaceLandmardkPaint.setStrokeWidth(2);
        mFaceLandmardkPaint.setStyle(Paint.Style.STROKE);
    }

    public void deInitialize() {
        synchronized (OnGetImageListener.this) {
            if (mFaceDet != null) {
                mFaceDet.release();
            }

            if (mWindow != null) {
                mWindow.release();
            }
        }
    }

    private void drawResizedBitmap(final Bitmap src, final Bitmap dst) {

        Display getOrient = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Point point = new Point();
        getOrient.getSize(point);
        int screen_width = point.x;
        int screen_height = point.y;
        Log.d(TAG, String.format("screen size (%d,%d)", screen_width, screen_height));
        if (screen_width < screen_height) {
            mScreenRotation = -90;
        } else {
            mScreenRotation = 0;
        }

        Assert.assertEquals(dst.getWidth(), dst.getHeight());
        final float minDim = Math.min(src.getWidth(), src.getHeight());

        final Matrix matrix = new Matrix();

        // We only want the center square out of the original rectangle.
        final float translateX = -Math.max(0, (src.getWidth() - minDim) / 2);
        final float translateY = -Math.max(0, (src.getHeight() - minDim) / 2);
        matrix.preTranslate(translateX, translateY);

        final float scaleFactor = dst.getHeight() / minDim;
        matrix.postScale(scaleFactor, scaleFactor);

        // Rotate around the center if necessary.
        if (mScreenRotation != 0) {
            matrix.postTranslate(-dst.getWidth() / 2.0f, -dst.getHeight() / 2.0f);
            matrix.postRotate(mScreenRotation);
            matrix.postTranslate(dst.getWidth() / 2.0f, dst.getHeight() / 2.0f);
        }

        final Canvas canvas = new Canvas(dst);
        canvas.drawBitmap(src, matrix, null);
    }

    class P {
        P(int startIdx, int endIdx, float factor) {
            this.startIdx = startIdx;
            this.endIdx = endIdx;
            this.factor = factor;
        }

        int startIdx;
        int endIdx;
        float factor;
    }

    // List of index pairs for interpolation describing the areas of eye lids. Format: [index1, index2, factor]
    P[] right_lid_area = {/* top */
            new P( 22, 42, 0.1f ),
            new P(  22, 42, 0.5f ),
            new P(  23, 43, 0.5f ),
            new P(  24, 43, 0.6f ),
            new P(  25, 44, 0.6f ),
            new P( 26, 45, 0.7f ),
                /* bottom*/
            new P(  26, 45, 0.1f ),
            new P( 25, 44, 0.2f ),
            new P( 24, 43, 0.1f ),
            new P( 23, 42, 0.2f )};


    Point lerp(Point pt1, Point pt2, float s) {
        Point result = new Point(
                (int) (pt1.x * s + pt2.x * (1.0 - s)),
                (int) (pt1.y * s + pt2.y * (1.0 - s)));
        return result;
    }

    ArrayList<Point>  landmarks;

    Point[] lerpLine(P[] lines) {
        Point points[] = new Point[lines.length];

        for (int i=0; i<lines.length; i++) {
            P point = lines[i];

            Point pt;
            Point lerp1 = landmarks.get(point.startIdx);
            Point lerp2 = landmarks.get(point.endIdx);

            pt = lerp(lerp1, lerp2, point.factor);

            points[i] = pt;
        }
        return points;
    }

    void drawClosedSpline(Canvas canvas, Point[] points) {
        Path path = new Path();
        path.moveTo(points[0].x, points[0].y);
        for (int i = 1; i < points.length; i+=2)
        {
            Point pt1 =  points[i];
            Point pt2 = points[(i+1)%points.length];
            path.quadTo(pt1.x, pt1.y, pt2.x, pt2.y);
        }
        canvas.drawPath(path, mFaceLandmardkPaint);
    }

    static Map<Integer, Integer> symmetry() {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(22, 21);
        map.put(23, 20);
        map.put(24, 19);
        map.put(25, 18);
        map.put(26, 17);
        map.put(42, 39);
        map.put(43, 38);
        map.put(44, 37);
        map.put(45, 36);
        map.put(46, 41);
        map.put(47, 40);
        map.put(16, 0);
        map.put(15, 1);
        map.put(14, 2);
        map.put(13, 3);
        map.put(12, 4);
        map.put(11, 5);
        map.put(10, 6);
        map.put(9, 7);
        map.put(8, 6);
        return map;
    }

    private Map<Integer, Integer> symmetry =  symmetry();

    P[] mirror(P controlPoints[]) {
        P[] result = new P[controlPoints.length];
        for (int i=0; i< controlPoints.length; i++) {
            P pt = controlPoints[i];
            P outPt = new P(symmetry.get(pt.startIdx), symmetry.get(pt.endIdx), pt.factor);
            result[i] = outPt;
        }
        return result;
    }

    /**
     * Draws the areas of both eye lids
     *
     * ctx: the canvas context
     */
    void drawLids(Canvas canvas) {
        Point[]  left_lid_points = lerpLine(mirror(right_lid_area));
        Point[] right_lid_points = lerpLine(right_lid_area);

        drawClosedSpline(canvas, left_lid_points);
        drawClosedSpline(canvas, right_lid_points);

        //drawControlPoints(ctx, left_lid_points);
        //drawControlPoints(ctx, right_lid_points);

        //drawArrows(ctx, left_lid_points);
        //drawArrows(ctx, right_lid_points);
    }

    @Override
    public void onImageAvailable(final ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            // No mutex needed as this method is not reentrant.
            if (mIsComputing) {
                image.close();
                return;
            }
            mIsComputing = true;

            Trace.beginSection("imageAvailable");

            final Plane[] planes = image.getPlanes();

            // Initialize the storage bitmaps once when the resolution is known.
            if (mPreviewWdith != image.getWidth() || mPreviewHeight != image.getHeight()) {
                mPreviewWdith = image.getWidth();
                mPreviewHeight = image.getHeight();

                Log.d(TAG, String.format("Initializing at size %dx%d", mPreviewWdith, mPreviewHeight));
                mRGBBytes = new int[mPreviewWdith * mPreviewHeight];
                mRGBframeBitmap = Bitmap.createBitmap(mPreviewWdith, mPreviewHeight, Config.ARGB_8888);
                mCroppedBitmap = Bitmap.createBitmap(600, 600, Config.ARGB_8888);

                mYUVBytes = new byte[planes.length][];
                for (int i = 0; i < planes.length; ++i) {
                    mYUVBytes[i] = new byte[planes[i].getBuffer().capacity()];
                }
            }

            for (int i = 0; i < planes.length; ++i) {
                planes[i].getBuffer().get(mYUVBytes[i]);
            }

            final int yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            ImageUtils.convertYUV420ToARGB8888(
                    mYUVBytes[0],
                    mYUVBytes[1],
                    mYUVBytes[2],
                    mRGBBytes,
                    mPreviewWdith,
                    mPreviewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    false);

            image.close();
        } catch (final Exception e) {
            if (image != null) {
                image.close();
            }
            Log.e(TAG, "Exception!", e);
            Trace.endSection();
            return;
        }

        mRGBframeBitmap.setPixels(mRGBBytes, 0, mPreviewWdith, 0, 0, mPreviewWdith, mPreviewHeight);
        drawResizedBitmap(mRGBframeBitmap, mCroppedBitmap);

        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(mCroppedBitmap);
        }

        mInferenceHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!new File(Constants.getFaceShapeModelPath()).exists()) {
                            FileUtils.copyFileFromRawToOthers(mContext, R.raw.shape_predictor_68_face_landmarks, Constants.getFaceShapeModelPath());
                        }

                        List<VisionDetRet> results;
                        synchronized (OnGetImageListener.this) {
                            results = mFaceDet.detect(mCroppedBitmap);
                        }
                        // Draw on bitmap
                        if (results != null) {
                            for (final VisionDetRet ret : results) {
                                float resizeRatio = 1.0f;
                                Rect bounds = new Rect();
                                bounds.left = (int) (ret.getLeft() * resizeRatio);
                                bounds.top = (int) (ret.getTop() * resizeRatio);
                                bounds.right = (int) (ret.getRight() * resizeRatio);
                                bounds.bottom = (int) (ret.getBottom() * resizeRatio);
                                Canvas canvas = new Canvas(mCroppedBitmap);
                                //canvas.drawRect(bounds, mFaceLandmardkPaint);

                                // Draw landmark
                                landmarks = ret.getFaceLandmarks();
                                /*
                                for (Point point : landmarks) {
                                    int pointX = (int) (point.x * resizeRatio);
                                    int pointY = (int) (point.y * resizeRatio);
                                    canvas.drawCircle(pointX, pointY, 2, mFaceLandmardkPaint);
                                }*/

                                drawLids(canvas);
                            }
                        }

                        mWindow.setRGBBitmap(mCroppedBitmap);
                        mIsComputing = false;
                    }
                });

        Trace.endSection();
    }
}
