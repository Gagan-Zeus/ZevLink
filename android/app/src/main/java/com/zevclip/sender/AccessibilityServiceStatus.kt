package com.zevclip.sender

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

object AccessibilityServiceStatus {
    private const val TAG = "ZevClipAccessibility"

    data class State(
        val enabled: Boolean,
        val serviceBound: Boolean,
        val lastServiceEvent: String,
        val diagnostic: String,
        val expectedComponent: String,
        val enabledServices: String
    )

    fun isEnabled(context: Context): Boolean {
        return currentState(context).enabled
    }

    fun currentState(context: Context): State {
        val expectedComponent = ComponentName(
            context.packageName,
            ClipboardAccessibilityService::class.java.name
        )
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val isEnabled = enabledServices
            .split(TextUtils.SimpleStringSplitter(':'))
            .any { serviceName -> matchesExpectedService(serviceName, expectedComponent) }

        val savedServiceBound = ZevClipPreferences.isAccessibilityServiceBound(context)

        return State(
            enabled = isEnabled,
            serviceBound = isEnabled && savedServiceBound,
            lastServiceEvent = ZevClipPreferences.lastAccessibilityServiceEvent(context),
            diagnostic = diagnosticMessage(isEnabled, enabledServices, savedServiceBound),
            expectedComponent = expectedComponent.flattenToString(),
            enabledServices = enabledServices
        )
    }

    fun logCurrentState(context: Context, reason: String) {
        val state = currentState(context)
        Log.i(
            TAG,
            "$reason: enabled=${state.enabled}, bound=${state.serviceBound}, " +
                "lastEvent=${state.lastServiceEvent}, expected=${state.expectedComponent}, " +
                "enabledServices=${state.enabledServices.ifBlank { "<empty>" }}, " +
                "diagnostic=${state.diagnostic}"
        )
    }

    private fun diagnosticMessage(
        isEnabled: Boolean,
        enabledServices: String,
        savedServiceBound: Boolean
    ): String {
        return when {
            !isEnabled && enabledServices.isBlank() ->
                "Android Settings does not list ZevLink. Re-enable the Accessibility Service; force-stop or OEM cleanup disabled it."
            !isEnabled ->
                "Android Settings lists Accessibility services, but not ZevLink."
            isEnabled && !savedServiceBound ->
                "Permission is enabled. Android may still be reconnecting the service."
            else ->
                "Permission is enabled and the service has connected."
        }
    }

    private fun matchesExpectedService(
        serviceName: String,
        expectedComponent: ComponentName
    ): Boolean {
        val normalizedServiceName = serviceName.trim()
        if (normalizedServiceName.isEmpty()) {
            return false
        }

        if (
            normalizedServiceName == expectedComponent.flattenToString() ||
            normalizedServiceName == expectedComponent.flattenToShortString()
        ) {
            return true
        }

        val enabledComponent = ComponentName.unflattenFromString(normalizedServiceName)
            ?: return false

        return enabledComponent.packageName == expectedComponent.packageName &&
            enabledComponent.className == expectedComponent.className
    }

    private fun String.split(splitter: TextUtils.SimpleStringSplitter): List<String> {
        splitter.setString(this)
        return buildList {
            while (splitter.hasNext()) {
                add(splitter.next())
            }
        }
    }
}
