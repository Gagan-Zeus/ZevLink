package com.zevclip.sender

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Base64
import com.zevclip.sender.airplay.RaopTestToneClient
import java.io.ByteArrayOutputStream

object AndroidNowPlayingReader {
    private var cachedArtworkKey: String? = null
    private var cachedArtworkBase64: String? = null

    fun current(context: Context): RaopTestToneClient.NowPlayingMetadata? {
        val controller = activeController(context) ?: return null
        val metadata = controller.metadata ?: return null
        val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM)
        val playbackState = controller.playbackState
        val durationMillis = metadata.longOrNull(MediaMetadata.METADATA_KEY_DURATION)
        val positionMillis = playbackState?.position?.takeIf { it >= 0 }
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING ||
            playbackState?.state == PlaybackState.STATE_BUFFERING ||
            playbackState?.state == PlaybackState.STATE_CONNECTING
        val artworkKey = listOf(title.orEmpty(), artist.orEmpty(), album.orEmpty()).joinToString("\u0001")
        val nowPlaying = RaopTestToneClient.NowPlayingMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMillis = durationMillis,
            positionMillis = positionMillis,
            isPlaying = isPlaying,
            artworkBase64 = artworkBase64(metadata, artworkKey)
        )
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

    private fun MediaMetadata.longOrNull(key: String): Long? {
        val value = getLong(key)
        return value.takeIf { it > 0 }
    }

    private fun artworkBase64(metadata: MediaMetadata, artworkKey: String): String? {
        if (cachedArtworkKey == artworkKey) {
            return cachedArtworkBase64
        }

        val artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val encoded = artwork?.scaledForNowPlaying()?.jpegBase64()
        cachedArtworkKey = artworkKey
        cachedArtworkBase64 = encoded
        return encoded
    }

    private fun Bitmap.scaledForNowPlaying(): Bitmap {
        val largestEdge = maxOf(width, height)
        if (largestEdge <= ARTWORK_MAX_EDGE_PX) return this

        val scale = ARTWORK_MAX_EDGE_PX.toFloat() / largestEdge.toFloat()
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    }

    private fun Bitmap.jpegBase64(): String {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private const val ARTWORK_MAX_EDGE_PX = 512
    private const val ARTWORK_JPEG_QUALITY = 88
}
