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

package com.juwan.lynx.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.juwan.lynx.service.LynxAccessibilityService
import com.juwan.lynx.service.LynxCaptureService

/**
 * Utility class for checking required permissions for Lynx Agent.
 * 
 * Required permissions:
 * 1. Accessibility Service - for UI操作 (click, swipe, type, etc.)
 * 2. MediaProjection - for screen capture
 */
object PermissionChecker {
    
    /**
     * Check if Accessibility Service is enabled.
     * 
     * @param context Application context
     * @return True if accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val service = LynxAccessibilityService.getInstance()
        return service != null
    }
    
    /**
     * Check if MediaProjection permission has been granted.
     * This checks if the LynxCaptureService has been initialized with permission.
     * 
     * @param context Application context
     * @return True if media projection permission is granted
     */
    fun isMediaProjectionGranted(): Boolean {
        val service = LynxCaptureService.getInstance()
        return service != null && service.isProjectionAuthorizationReady
    }
    
    /**
     * Check if all required permissions are granted.
     * 
     * @param context Application context
     * @return True if all permissions are granted
     */
    fun areAllPermissionsGranted(context: Context): Boolean {
        return isAccessibilityServiceEnabled() &&
            isMediaProjectionGranted() &&
            canDrawOverlays(context)
    }
    
    /**
     * Get list of missing permissions.
     * 
     * @param context Application context
     * @return List of missing permission names
     */
    fun getMissingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        
        if (!isAccessibilityServiceEnabled()) {
            missing.add("无障碍服务（Accessibility Service）")
        }
        
        if (!isMediaProjectionGranted()) {
            missing.add("屏幕捕获权限（Screen Capture）")
        }
        if (!canDrawOverlays(context)) {
            missing.add("悬浮窗权限（Draw Over Other Apps）")
        }
        
        return missing
    }
    
    /**
     * Open accessibility settings to enable the service.
     * 
     * @param context Application context
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * Get user-friendly error message for missing permissions.
     * 
     * @param context Application context
     * @return Error message describing missing permissions
     */
    fun getPermissionErrorMessage(context: Context): String {
        val missing = getMissingPermissions(context)
        
        if (missing.isEmpty()) {
            return ""
        }
        
        val builder = StringBuilder()
        builder.append("⚠️ 缺少必要权限\n\n")
        builder.append("执行任务前需要开启以下权限：\n\n")
        
        missing.forEachIndexed { index, permission ->
            builder.append("${index + 1}. $permission\n")
        }
        
        builder.append("\n请在设置中开启这些权限后再试。")
        
        return builder.toString()
    }
    
    /**
     * Get detailed permission setup instructions.
     * 
     * @return Detailed instructions for setting up permissions
     */
    fun getPermissionInstructions(): String {
        return """
            📋 权限设置说明
            
            Lynx Agent 需要以下权限才能正常工作：
            
            1️⃣ 无障碍服务（Accessibility Service）
            - 用途：执行屏幕操作（点击、滑动、输入等）
            - 设置方法：
              • 打开系统设置 → 辅助功能 → 已安装的服务
              • 找到 "Lynx Agent" 并开启
              • 授予权限
            
            2️⃣ 屏幕捕获权限（Screen Capture）
            - 用途：获取屏幕截图用于视觉理解
            - 设置方法：
              • 在应用设置页面点击"授予屏幕捕获权限"
              • 在弹出的系统对话框中选择"立即开始"

            3️⃣ 悬浮窗权限（Overlay）
            - 用途：在任意应用上方显示安全确认全屏浮层
            - 设置方法：
              • 打开系统设置 → 应用管理 → Lynx Agent → 显示在其他应用上层
              • 开启允许显示

            ⚠️ 注意事项：
            - 这三个权限都是必需的，缺一不可
            - 权限仅用于执行您指定的任务
            - 您可以随时在系统设置中撤销权限
        """.trimIndent()
    }
}
