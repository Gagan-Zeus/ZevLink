package com.zevclip.sender

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object AndroidBatteryStatus {
    fun percentage(context: Context): Int? {
        val appContext = context.applicationContext
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val capacity = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        capacity?.takeIf { it in 0..100 }?.let { return it }

        val batteryIntent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        return ((level.toLong() * 100L) / scale)
            .toInt()
            .takeIf { it in 0..100 }
    }
}
