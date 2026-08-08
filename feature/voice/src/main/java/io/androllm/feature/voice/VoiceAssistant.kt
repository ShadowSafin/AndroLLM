package io.androllm.feature.voice

import android.content.Context
import android.content.Intent
import io.androllm.feature.voice.service.VoiceAssistantService

/**
 * Public entry point for the always-on voice assistant. The settings page and
 * any future surfaces call these two functions — nothing else needs to know
 * about the service.
 */
object VoiceAssistant {

    fun start(context: Context) {
        val intent = Intent(context, VoiceAssistantService::class.java)
            .setAction(VoiceAssistantService.ACTION_START)
        runCatching { context.startForegroundService(intent) }
    }

    fun stop(context: Context) {
        val intent = Intent(context, VoiceAssistantService::class.java)
            .setAction(VoiceAssistantService.ACTION_STOP)
        runCatching { context.startService(intent) }
    }
}
