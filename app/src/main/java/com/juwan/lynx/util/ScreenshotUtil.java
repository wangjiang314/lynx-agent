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

package com.juwan.lynx.util;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.juwan.lynx.service.LynxCaptureService;

import java.io.ByteArrayOutputStream;

/**
 * Screenshot utility class for silent screen capture using MediaProjection.
 * Reused and adapted from tars_4_rpa2.
 */
public class ScreenshotUtil {
    public static final int REQUEST_MEDIA_PROJECTION = 10086;

    /**
     * Request MediaProjection permission (call from Activity)
     */
    public static void requestScreenshotPermission(Activity activity) {
        MediaProjectionManager mpm = (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent intent = mpm.createScreenCaptureIntent();
        activity.startActivityForResult(intent, REQUEST_MEDIA_PROJECTION);
    }

    /**
     * Callback interface for screenshot results
     */
    public interface ScreenshotCallback {
        void onScreenshotBase64(String base64, int width, int height);

        void onError(String reason);
    }

    /**
     * Asynchronous screenshot capture (requires prior authorization)
     */
    public static void takeScreenshot(Context context, int resultCode, Intent data, ScreenshotCallback callback) {
        IntentFilter filter = new IntentFilter(LynxCaptureService.ACTION_SCREENSHOT_READY);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String path = intent.getStringExtra(LynxCaptureService.EXTRA_SCREENSHOT_PATH);
                // CRITICAL FIX: Read file directly to base64 instead of decoding to Bitmap first
                // The file is already compressed to 120KB by LynxCaptureService
                // Decoding to Bitmap and re-compressing would make it much larger
                java.io.File file = new java.io.File(path);
                String base64 = BitmapUtil.fileToBase64(file);
                
                // Still need to decode to get dimensions
                Bitmap bmp = BitmapFactory.decodeFile(path);
                int width = bmp != null ? bmp.getWidth() : 0;
                int height = bmp != null ? bmp.getHeight() : 0;
                if (bmp != null) {
                    bmp.recycle();
                }
                
                callback.onScreenshotBase64(base64, width, height);
                context.unregisterReceiver(this);
            }
        };
        // Use ContextCompat.registerReceiver for all versions to ensure proper flags
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        Intent serviceIntent = new Intent(context, LynxCaptureService.class);
        serviceIntent.putExtra(LynxCaptureService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(LynxCaptureService.EXTRA_DATA, data);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}
