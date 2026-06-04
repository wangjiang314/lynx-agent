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

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Bitmap utility class for image processing.
 * Reused from tars_4_rpa2.
 */
public class BitmapUtil {
    private static final String TAG = "BitmapUtil";
    
    /**
     * Convert Bitmap to Base64 string
     * IMPORTANT: Uses adaptive compression to keep size under control
     *
     * @param bitmap source bitmap
     * @return base64 string
     */
    public static String toBase64(Bitmap bitmap) {
        // Adaptive compression - start at 80% and reduce if needed
        int quality = 80;
        int minQuality = 20;
        int step = 5;
        int targetMaxKB = 120; // Target max 120KB (same as tars_4_rpa2)
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        
        // Reduce quality until size is acceptable
        while (baos.toByteArray().length / 1024 > targetMaxKB && quality > minQuality) {
            baos.reset();
            quality -= step;
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        }
        
        byte[] bytes = baos.toByteArray();
        Log.d(TAG, "Bitmap compressed: " + (bytes.length / 1024) + "KB, quality=" + quality);
        
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
    
    /**
     * Convert file to Base64 string directly (more efficient for already compressed files)
     *
     * @param file source file
     * @return base64 string
     */
    public static String fileToBase64(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            fis.close();
            Log.d(TAG, "File to base64: " + (bytes.length / 1024) + "KB");
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error converting file to base64", e);
            return "";
        }
    }

    /**
     * Convert Base64 string to Bitmap
     *
     * @param base64 base64 string
     * @return Bitmap object, null on failure
     */
    public static Bitmap base64ToBitmap(String base64) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
