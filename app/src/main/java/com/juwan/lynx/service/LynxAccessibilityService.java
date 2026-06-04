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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Lynx Accessibility Service - provides gesture execution capabilities
 * (click, swipe, type, back, home) for autonomous screen operations.
 * Reused and adapted from tars_4_rpa2 MyAccessibilityService.
 */
public class LynxAccessibilityService extends AccessibilityService {
    private static LynxAccessibilityService instance;
    private static final String TAG = "LynxAccessibilityService";
    private static final String NOTIFICATION_CHANNEL_ID = "lynx_accessibility_channel";
    private static final int NOTIFICATION_ID = 1001;
    private volatile String currentPackageName;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "onServiceConnected: Accessibility service connected");
        startForegroundService();
    }

    public static LynxAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        final int eventType = event.getEventType();
        CharSequence packageName = event.getPackageName();
        if (packageName != null && packageName.length() > 0) {
            currentPackageName = packageName.toString();
        }
        // Keep logs minimal: only selected high-signal events at verbose level.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
//            Log.v(TAG, "onAccessibilityEvent: type=" + eventType + ", package=" + event.getPackageName());
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "onInterrupt: Accessibility service interrupted");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "onUnbind: Accessibility service unbound");
        stopForegroundService();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy: Accessibility service destroyed");
        stopForegroundService();
    }

    /**
     * Start foreground service with persistent notification
     */
    private void startForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Lynx Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Lynx Agent autonomous operation service");
                channel.setShowBadge(false);
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }

            Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Lynx Agent Running")
                    .setContentText("Accessibility service active")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            Log.i(TAG, "Foreground service started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service: " + e.getMessage(), e);
        }
    }

    /**
     * Stop foreground service
     */
    private void stopForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            Log.i(TAG, "Foreground service stopped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop foreground service: " + e.getMessage(), e);
        }
    }

    /**
     * Perform single click at (x, y)
     */
    public void performClick(int x, int y) {
        if (x < 0 || y < 0) {
            Log.e(TAG, "performClick: Invalid coordinates detected, skipping gesture x=" + x + ", y=" + y);
            return;
        }
        Log.i(TAG, "performClick: x=" + x + ", y=" + y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, y);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 250));
        GestureDescription gesture = builder.build();
        this.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.i(TAG, "performClick: onCancelled");
                super.onCancelled(gestureDescription);
            }

            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "performClick: onCompleted");
                super.onCompleted(gestureDescription);
            }
        }, null);
    }

    /**
     * Perform long press at (x, y)
     */
    public void performLongPress(int x, int y) {
        if (x < 0 || y < 0) {
            Log.e(TAG, "performLongPress: Invalid coordinates detected, skipping gesture x=" + x + ", y=" + y);
            return;
        }
        Log.i(TAG, "performLongPress: x=" + x + ", y=" + y);
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 800);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        dispatchGesture(builder.build(), null, null);
    }

    /**
     * Perform swipe/drag gesture
     */
    public void performSwipe(int startX, int startY, int endX, int endY) {
        Log.i(TAG, "performSwipe: startX=" + startX + ", startY=" + startY + ", endX=" + endX + ", endY=" + endY);

        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            Log.e(TAG, "performSwipe: Invalid coordinates detected, skipping gesture");
            return;
        }

        try {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 400);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);
            dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.e(TAG, "performSwipe: Error creating gesture", e);
        }
    }

    /**
     * Perform global back action
     */
    public void performBack() {
        Log.i(TAG, "performBack: Performing global back action");
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * Perform global home action
     */
    public void performHome() {
        Log.i(TAG, "performHome: Performing global home action");
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /**
     * Type text in focused input field
     */
    public boolean performType(String content) {
        Log.i(TAG, "performType: content=" + content);
        if (content == null) {
            Log.w(TAG, "performType: content is null");
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "performType: Unsupported SDK");
            return false;
        }

        AccessibilityNodeInfo node = getRootInActiveWindow();
        if (node == null) {
            Log.w(TAG, "performType: Active window is null");
            return false;
        }

        AccessibilityNodeInfo focus = findFocusInput(node);
        if (focus == null) {
            focus = findEditableInput(node);
        }
        if (focus == null) {
            Log.w(TAG, "performType: No editable input found");
            return false;
        }

        if (!focus.isFocused()) {
            focus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content);
        boolean success = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (success) {
            Log.i(TAG, "performType: Text set successfully");
        } else {
            Log.w(TAG, "performType: ACTION_SET_TEXT returned false");
        }
        return success;
    }

    /**
     * Type text into an editable node that overlaps the provided target bounds.
     * Does not fall back to an arbitrary editable node outside the target region.
     */
    public boolean performType(String content, int left, int top, int right, int bottom) {
        Log.i(TAG, "performType(targeted): content=" + content + ", bounds=" + left + "," + top + "," + right + "," + bottom);
        if (content == null) {
            Log.w(TAG, "performType(targeted): content is null");
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "performType(targeted): Unsupported SDK");
            return false;
        }

        AccessibilityNodeInfo node = getRootInActiveWindow();
        if (node == null) {
            Log.w(TAG, "performType(targeted): Active window is null");
            return false;
        }

        Rect targetBounds = new Rect(left, top, right, bottom);
        AccessibilityNodeInfo focus = findTargetEditableInput(node, targetBounds);
        if (focus == null) {
            Log.w(TAG, "performType(targeted): No editable input overlapping target bounds");
            return false;
        }

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content);
        boolean success = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (success) {
            Log.i(TAG, "performType(targeted): Text set successfully");
        } else {
            Log.w(TAG, "performType(targeted): ACTION_SET_TEXT returned false");
        }
        return success;
    }

    /**
     * Check whether current window has focused editable input.
     */
    public boolean hasFocusedEditableInput() {
        AccessibilityNodeInfo node = getRootInActiveWindow();
        if (node == null) return false;
        return findFocusInput(node) != null;
    }

    /**
     * Trigger IME enter action on focused editable input.
     * Returns false when no focused input is available or IME action is unsupported.
     */
    public boolean performImeAction() {
        AccessibilityNodeInfo node = getRootInActiveWindow();
        if (node == null) {
            Log.w(TAG, "performImeAction: Active window is null");
            return false;
        }

        AccessibilityNodeInfo focus = findFocusInput(node);
        if (focus == null) {
            focus = findEditableInput(node);
        }
        if (focus == null) {
            Log.w(TAG, "performImeAction: No editable input found");
            return false;
        }

        if (!focus.isFocused()) {
            focus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean success = focus.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()
            );
            if (success) {
                Log.i(TAG, "performImeAction: IME enter action dispatched");
                return true;
            }
        }

        Log.w(TAG, "performImeAction: IME enter action unsupported or failed");
        return false;
    }

    /**
     * Trigger IME action only on the editable node that overlaps the provided target bounds.
     */
    public boolean performImeAction(int left, int top, int right, int bottom) {
        AccessibilityNodeInfo node = getRootInActiveWindow();
        if (node == null) {
            Log.w(TAG, "performImeAction(targeted): Active window is null");
            return false;
        }

        Rect targetBounds = new Rect(left, top, right, bottom);
        AccessibilityNodeInfo focus = findTargetEditableInput(node, targetBounds);
        if (focus == null) {
            Log.w(TAG, "performImeAction(targeted): No editable input overlapping target bounds");
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean success = focus.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()
            );
            if (success) {
                Log.i(TAG, "performImeAction(targeted): IME enter action dispatched");
                return true;
            }
        }

        Log.w(TAG, "performImeAction(targeted): IME enter action unsupported or failed");
        return false;
    }

    /**
     * Helper method to find focused input field
     */
    private AccessibilityNodeInfo findFocusInput(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocused() && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo res = findFocusInput(node.getChild(i));
            if (res != null) return res;
        }
        return null;
    }

    private AccessibilityNodeInfo findEditableInput(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo res = findEditableInput(node.getChild(i));
            if (res != null) return res;
        }
        return null;
    }

    private AccessibilityNodeInfo findTargetEditableInput(AccessibilityNodeInfo root, Rect targetBounds) {
        AccessibilityNodeInfo focused = findFocusInput(root);
        if (isEditableInputOverlapping(focused, targetBounds)) {
            logEditableCandidate("findTargetEditableInput: using focused candidate", focused, targetBounds, scoreEditableInput(focused, targetBounds));
            return focused;
        }

        AccessibilityNodeInfo candidate = findEditableInputOverlapping(root, targetBounds);
        if (candidate == null) {
            return null;
        }
        if (!candidate.isFocused()) {
            boolean focusApplied = candidate.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (!focusApplied) {
                Log.w(TAG, "findTargetEditableInput: Failed to focus overlapping editable input, using candidate directly");
                logEditableCandidate("findTargetEditableInput: fallback to unfocused candidate", candidate, targetBounds, scoreEditableInput(candidate, targetBounds));
                return candidate;
            }
        }

        AccessibilityNodeInfo refreshedRoot = getRootInActiveWindow();
        AccessibilityNodeInfo refreshedFocused = refreshedRoot != null ? findFocusInput(refreshedRoot) : null;
        if (isEditableInputOverlapping(refreshedFocused, targetBounds)) {
            logEditableCandidate("findTargetEditableInput: using refreshed focused candidate", refreshedFocused, targetBounds, scoreEditableInput(refreshedFocused, targetBounds));
            return refreshedFocused;
        }

        Log.w(TAG, "findTargetEditableInput: Focus did not move onto target editable input, using original candidate");
        logEditableCandidate("findTargetEditableInput: fallback to original candidate", candidate, targetBounds, scoreEditableInput(candidate, targetBounds));
        return candidate;
    }

    private AccessibilityNodeInfo findEditableInputOverlapping(AccessibilityNodeInfo node, Rect targetBounds) {
        if (node == null) return null;
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectEditableInputsOverlapping(node, targetBounds, candidates);
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MIN_VALUE;
        for (AccessibilityNodeInfo candidate : candidates) {
            long score = scoreEditableInput(candidate, targetBounds);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best != null) {
            logEditableCandidate("findEditableInputOverlapping: selected candidate", best, targetBounds, bestScore);
        }
        return best;
    }

    private void collectEditableInputsOverlapping(
            AccessibilityNodeInfo node,
            Rect targetBounds,
            List<AccessibilityNodeInfo> candidates
    ) {
        if (node == null) return;
        if (isEditableInputOverlapping(node, targetBounds)) {
            candidates.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectEditableInputsOverlapping(node.getChild(i), targetBounds, candidates);
        }
    }

    private boolean isEditableInputOverlapping(AccessibilityNodeInfo node, Rect targetBounds) {
        if (node == null || !node.isEditable()) return false;
        Rect nodeBounds = new Rect();
        node.getBoundsInScreen(nodeBounds);
        if (nodeBounds.width() <= 0 || nodeBounds.height() <= 0) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && !node.isVisibleToUser()) {
            return false;
        }
        return Rect.intersects(nodeBounds, targetBounds);
    }

    private long scoreEditableInput(AccessibilityNodeInfo node, Rect targetBounds) {
        if (node == null) return Long.MIN_VALUE;
        Rect nodeBounds = new Rect();
        node.getBoundsInScreen(nodeBounds);
        if (nodeBounds.width() <= 0 || nodeBounds.height() <= 0) return Long.MIN_VALUE;
        Rect overlapBounds = new Rect(nodeBounds);
        if (!overlapBounds.intersect(targetBounds)) return Long.MIN_VALUE;
        long overlapArea = (long) overlapBounds.width() * overlapBounds.height();
        long centerDistance = Math.abs(nodeBounds.centerX() - targetBounds.centerX())
                + Math.abs(nodeBounds.centerY() - targetBounds.centerY());
        long widthDelta = Math.abs(nodeBounds.width() - targetBounds.width());
        long heightDelta = Math.abs(nodeBounds.height() - targetBounds.height());
        long score = overlapArea * 1000L - centerDistance * 10L - widthDelta * 3L - heightDelta * 3L;
        if (node.isFocused()) {
            score += 1_000_000L;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 || node.isVisibleToUser()) {
            score += 500_000L;
        }
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            score += 1_000L;
        }
        CharSequence contentDescription = node.getContentDescription();
        if (contentDescription != null && contentDescription.length() > 0) {
            score += 500L;
        }
        return score;
    }

    private void logEditableCandidate(String prefix, AccessibilityNodeInfo node, Rect targetBounds, long score) {
        if (node == null) return;
        Rect nodeBounds = new Rect();
        node.getBoundsInScreen(nodeBounds);
        boolean visible = Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 || node.isVisibleToUser();
        Log.i(
                TAG,
                prefix
                        + " target=" + targetBounds
                        + " bounds=" + nodeBounds
                        + " focused=" + node.isFocused()
                        + " visible=" + visible
                        + " score=" + score
                        + " text=" + safeNodeText(node.getText())
                        + " desc=" + safeNodeText(node.getContentDescription())
        );
    }

    private String safeNodeText(CharSequence value) {
        if (value == null) return "";
        String text = value.toString().trim();
        if (text.length() <= 60) return text;
        return text.substring(0, 57) + "...";
    }

    /**
     * Launch application by package name
     */
    public void performOpenApp(String appName) {
        Log.i(TAG, "performOpenApp: appName=" + appName);
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(appName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.i(TAG, "performOpenApp: App launched");
        } else {
            Log.w(TAG, "performOpenApp: App not found: " + appName);
        }
    }

    /**
     * Dump UI tree for screen observation
     */
    public AccessibilityNodeInfo dumpUITree() {
        return getRootInActiveWindow();
    }

    /**
     * Get current package name
     */
    public String getCurrentPackageName() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && root.getPackageName().length() > 0) {
            currentPackageName = root.getPackageName().toString();
        }
        return currentPackageName;
    }
}
