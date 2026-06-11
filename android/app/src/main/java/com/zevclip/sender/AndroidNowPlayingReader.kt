package com.zevclip.sender

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.zevclip.sender.airplay.RaopTestToneClient

object AndroidNowPlayingReader {
    fun current(context: Context): RaopTestToneClient.NowPlayingMetadata? {
        val controller = activeController(context) ?: return null
        val metadata = controller.metadata ?: return null
        val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM)
        val nowPlaying = RaopTestToneClient.NowPlayingMetadata(title, artist, album)
        return if (nowPlaying.isEmpty()) null else nowPlaying
    }

    fun activeController(context: Context): MediaController? {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, AndroidNotificationMirrorService::class.java)
        val sessions = runCatching { manager.getActiveSessions(listener) }.getOrElse { emptyList() }
        if (sessions.isEmpty()) return null

        return sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: sessions.firstOrNull { it.metadata != null } ?: sessions.firstOrNull()
    }

    private fun MediaMetadata.text(key: String): String? {
        return getString(key)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
