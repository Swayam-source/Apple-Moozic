package com.music.zen.data.discord

import android.content.Context
import com.music.zen.data.model.Song
import com.music.zen.data.model.artworkAt
import com.music.zen.data.model.youTubeThumbnailFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class DiscordRPC(
    val context: Context,
    @Suppress("UNUSED_PARAMETER") token: String,
) {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
        @Suppress("UNUSED_PARAMETER") useDetails: Boolean = false,
        @Suppress("UNUSED_PARAMETER") status: String = "online",
        @Suppress("UNUSED_PARAMETER") button1Text: String = "",
        @Suppress("UNUSED_PARAMETER") button1Visible: Boolean = true,
        @Suppress("UNUSED_PARAMETER") button2Text: String = "",
        @Suppress("UNUSED_PARAMETER") button2Visible: Boolean = true,
        activityType: String = "listening",
        activityName: String = "",
        audioQuality: String? = null,
    ) = runCatching {
        val appId = DiscordAuthManager.getApplicationId(context).toLongOrNull() ?: return@runCatching
        if (appId <= 0L) return@runCatching

        val currentTime = System.currentTimeMillis()
        val adjustedPlaybackTime = (currentPlaybackTimeMillis / playbackSpeed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime
        val remainingDuration = (durationMillis - currentPlaybackTimeMillis).coerceAtLeast(0L)
        val adjustedRemainingDuration = (remainingDuration / playbackSpeed).toLong()

        val songTitleWithRate = if (playbackSpeed != 1.0f) {
            "${song.title} [${String.format(Locale.ROOT, "%.2fx", playbackSpeed)}]"
        } else {
            song.title
        }

        val resolvedAppName = activityName.ifBlank { DEFAULT_APP_NAME }

        val publicCoverUrl = song.artworkAt(ART_PX)
            ?.let { youTubeThumbnailFallback(it) ?: it }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: DiscordAuthManager.resolveItunesArtwork(song.title, song.artist)

        DiscordNative.initialize(appId)
        DiscordAuthManager.getValidAccessToken(context)?.let { oauthToken ->
            DiscordNative.updateToken(oauthToken)
        }

        DiscordNative.updateRichPresence(
            activityName = resolvedAppName,
            activityType = activityType,
            title = songTitleWithRate,
            artist = song.artist,
            album = song.albumName ?: audioQuality ?: resolvedAppName,
            artworkUrl = publicCoverUrl,
            startTimestampSec = calculatedStartTime / 1000L,
            endTimestampSec = (currentTime + adjustedRemainingDuration) / 1000L
        )
    }

    suspend fun close() {
        DiscordNative.clearRichPresence()
    }

    fun closeRPC() {
        cleanupScope.launch {
            DiscordNative.clearRichPresence()
        }
    }

    companion object {
        const val DEFAULT_APP_NAME = "Apple Music"
        const val PROJECT_URL = "https://github.com/pxper_-source/Apple-Moozic"
        const val DEFAULT_BUTTON_1 = "Listen on YouTube Music"
        const val DEFAULT_BUTTON_2 = "Apple Music"
        private const val ART_PX = 480

        fun watchUrl(song: Song): String =
            "https://music.youtube.com/watch?v=${song.videoId}"

        fun resolveVariables(text: String, song: Song): String {
            return text
                .replace("{song_name}", song.title)
                .replace("{artist_name}", song.artist)
                .replace("{album_name}", song.albumName ?: "")
        }
    }
}
