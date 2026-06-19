package com.zevclip.sender

import android.os.Bundle
import android.service.voice.VoiceInteractionService

class ZevVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle.EMPTY, 0)
    }

    companion object {
        const val EXTRA_INITIAL_COMMAND = "zev_initial_command"
    }
}
