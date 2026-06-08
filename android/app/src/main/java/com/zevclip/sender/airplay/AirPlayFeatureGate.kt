package com.zevclip.sender.airplay

import android.content.Context
import com.zevclip.sender.ZevClipPreferences

object AirPlayFeatureGate {
    fun isEnabled(context: Context): Boolean {
        return ZevClipPreferences.isExperimentalAirPlayEnabled(context)
    }

    fun setEnabled(context: Context, isEnabled: Boolean) {
        ZevClipPreferences.setExperimentalAirPlayEnabled(context, isEnabled)
    }
}
