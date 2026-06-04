package com.zevclip.sender

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

object AccessibilityServiceStatus {
    fun isEnabled(context: Context): Boolean {
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        val expectedClassName = ClipboardAccessibilityService::class.java.name

        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                service.resolveInfo.serviceInfo.packageName == context.packageName &&
                    service.resolveInfo.serviceInfo.name == expectedClassName
            }
    }
}

