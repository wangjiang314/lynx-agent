/*
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

package com.juwan.lynx.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.graphics.Rect;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lynx Screen Capture Service - captures screen using MediaProjection with JPEG compression.
 * Reused and adapted from tars_4_rpa2 ScreenCaptureService.
 */
public class LynxCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_SCREENSHOT_READY = "com.juwan.lynx.ACTION_SCREENSHOT_READY";
    public static final String EXTRA_SCREENSHOT_PATH = "screenshotPath";
    public static final String NOTIFICATION_CHANNEL_ID = "lynx_screencap_channel";
    private static final String TAG = "LynxCaptureService";
    private static LynxCaptureService instance;
    private Bitmap lastScreenshot;
    private final Object screenshotLock = new Object();
    private volatile int cachedResultCode = Activity.RESULT_CANCELED;
    private volatile Intent cachedProjectionData;
    private volatile long lastCaptureAtMs = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static LynxCaptureService getInstance() {
        return instance;
    }

    /**
     * Whether this service currently holds a valid MediaProjection authorization payload.
     * Note: service instance existence alone does not guarantee screenshot capability.
     */
    public boolean isProjectionAuthorizationReady() {
        return cachedResultCode == Activity.RESULT_OK && cachedProjectionData != null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, createNotification());
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (resultCode == Activity.RESULT_OK && data != null) {
            cachedResultCode = resultCode;
            // Keep a local copy so we can refresh screenshot in later iterations.
            cachedProjectionData = new Intent(data);
        }
        takeScreenshot(resultCode, data, null);
        return START_NOT_STICKY;
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Screen Capture Service")
                .setContentText("Capturing screen...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Compress and save bitmap with adaptive quality
     */
    private File compressAndSaveBitmap(Bitmap bitmap, File outFile, int targetMaxKB) throws java.io.IOException {
        int quality = 80;
        int minQuality = 20;
        int step = 5;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        while (baos.toByteArray().length / 1024 > targetMaxKB && quality > minQuality) {
            baos.reset();
            quality -= step;
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        }
        java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
        fos.write(baos.toByteArray());
        fos.close();
        baos.close();
        Log.i(TAG, "Final screenshot size: " + (outFile.length() / 1024) + "KB, quality=" + quality);
        return outFile;
    }

    /**
     * Capture screenshot as Base64-encoded string.
     * Returns the last captured screenshot if available.
     *
     * @return Base64-encoded screenshot
     */
    public String captureBase64() {
        synchronized (screenshotLock) {
            if (lastScreenshot != null) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                lastScreenshot.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] bytes = baos.toByteArray();
                String rawBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                return sanitizeBase64(rawBase64);
            }
        }
        return "";
    }

    /**
     * Refresh screenshot using cached MediaProjection authorization and wait until capture completes.
     *
     * @param timeoutMs max wait time
     * @return true if a refresh attempt completed and screenshot exists
     */
    public boolean refreshScreenshotBlocking(long timeoutMs) {
        Intent projectionData = cachedProjectionData;
        if (cachedResultCode != Activity.RESULT_OK || projectionData == null) {
            Log.w(TAG, "refreshScreenshotBlocking skipped: projection authorization not ready");
            return false;
        }

        final long now = System.currentTimeMillis();
        if (now - lastCaptureAtMs < 250L) {
            synchronized (screenshotLock) {
                return lastScreenshot != null;
            }
        }

        CountDownLatch latch = new CountDownLatch(1);
        takeScreenshot(cachedResultCode, new Intent(projectionData), latch::countDown);
        try {
            latch.await(Math.max(200L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "refreshScreenshotBlocking interrupted");
        }
        synchronized (screenshotLock) {
            return lastScreenshot != null;
        }
    }

    /**
     * Sanitize Base64 payload for vision APIs.
     * Removes whitespace/newlines and optional data URL prefix if present.
     */
    private String sanitizeBase64(String base64) {
        if (base64 == null) return "";
        String sanitized = base64
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
                .trim();

        // Defensive: strip data URL prefix if it was accidentally included upstream
        int commaIndex = sanitized.indexOf(",");
        if (sanitized.startsWith("data:image/") && commaIndex >= 0 && commaIndex < sanitized.length() - 1) {
            sanitized = sanitized.substring(commaIndex + 1);
        }
        return sanitized;
    }

    /**
     * Capture screenshot as Bitmap.
     * Returns the last captured screenshot if available.
     *
     * @return Screenshot bitmap or null
     */
    public Bitmap captureAsBitmap() {
        synchronized (screenshotLock) {
            return lastScreenshot;
        }
    }

    /**
     * Take screenshot using MediaProjection
     */
    private void takeScreenshot(int resultCode, Intent data, Runnable completion) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            MediaProjection projection = mpm.getMediaProjection(resultCode, data);
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            int width;
            int height;
            int density;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowMetrics windowMetrics = wm.getCurrentWindowMetrics();
                Rect bounds = windowMetrics.getBounds();
                width = Math.max(1, bounds.width());
                height = Math.max(1, bounds.height());
                density = getResources().getDisplayMetrics().densityDpi;
            } else {
                DisplayMetrics metrics = new DisplayMetrics();
                if (!populateLegacyDisplayMetrics(wm, metrics)) {
                    metrics = getResources().getDisplayMetrics();
                }
                width = Math.max(1, metrics.widthPixels);
                height = Math.max(1, metrics.heightPixels);
                density = metrics.densityDpi;
            }
            final ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            VirtualDisplay virtualDisplay = projection.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Image image = imageReader.acquireLatestImage();
                if (image != null) {
                    int imgWidth = image.getWidth();
                    int imgHeight = image.getHeight();
                    final Image.Plane[] planes = image.getPlanes();
                    final ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * imgWidth;
                    Bitmap bitmap = Bitmap.createBitmap(
                            imgWidth + rowPadding / pixelStride,
                            imgHeight, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);
                    image.close();
                    Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, imgWidth, imgHeight);
                    bitmap.recycle();

                    // Store the screenshot for later retrieval
                    synchronized (screenshotLock) {
                        if (lastScreenshot != null) {
                            lastScreenshot.recycle();
                        }
                        lastScreenshot = cropped;
                        lastCaptureAtMs = System.currentTimeMillis();
                    }

                    imageReader.close();
                    virtualDisplay.release();
                    projection.stop();
                    // Save to temporary file
                    try {
                        File cacheDir = getCacheDir();
                        File imageFile = new File(cacheDir, "screenshot.jpg");
                        compressAndSaveBitmap(cropped, imageFile, 120); // Target max 120KB
                        // Send broadcast with explicit package
                        Intent ready = new Intent(ACTION_SCREENSHOT_READY);
                        ready.setPackage(getPackageName());
                        ready.putExtra(EXTRA_SCREENSHOT_PATH, imageFile.getAbsolutePath());
                        sendBroadcast(ready);
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving screenshot", e);
                    }
                    if (completion != null) completion.run();
                } else {
                    imageReader.close();
                    virtualDisplay.release();
                    projection.stop();
                    if (completion != null) completion.run();
                }
            }, 300);
        } catch (Exception e) {
            Log.e(TAG, "Error taking screenshot", e);
            if (completion != null) completion.run();
        }
    }

    @SuppressWarnings("deprecation")
    private boolean populateLegacyDisplayMetrics(WindowManager wm, DisplayMetrics outMetrics) {
        android.view.Display display = wm.getDefaultDisplay();
        if (display == null) return false;
        display.getRealMetrics(outMetrics);
        return outMetrics.widthPixels > 0 && outMetrics.heightPixels > 0;
    }
}
