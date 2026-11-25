package com.music.bitchord.data.discord

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.youTubeThumbnailFallback
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import java.util.Locale

class DiscordRPC(
    val context: Context,
    private val token: String,
) : KizzyRPC(
    token = token.removePrefix(DiscordAuthManager.OAUTH_TOKEN_PREFIX),
    os = "Android",
    browser = "Discord Android",
    device = android.os.Build.DEVICE,
    userAgent = SuperProperties.userAgent,
    superPropertiesBase64 = SuperProperties.superPropertiesBase64,
) {
    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
        useDetails: Boolean = false,
        status: String = "online",
        button1Text: String = "",
        button1Visible: Boolean = true,
        button2Text: String = "",
        button2Visible: Boolean = true,
        activityType: String = "listening",
        activityName: String = "",
        audioQuality: String? = null,
    ) = runCatching {
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

        // Resolve public artwork URL (YouTube thumbnail or iTunes Search API fallback for local audio)
        val publicCoverUrl = song.artworkAt(ART_PX)
            ?.let { youTubeThumbnailFallback(it) ?: it }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: DiscordAuthManager.resolveItunesArtwork(song.title, song.artist)

        // Primary path: Official Discord Social SDK via JNI
        val oauthToken = DiscordAuthManager.getValidAccessToken(context)
        if (!oauthToken.isNullOrBlank()) {
            DiscordNative.initialize()
            DiscordNative.updateToken(oauthToken)
            DiscordNative.updateRichPresence(
                title = songTitleWithRate,
                artist = song.artist,
                album = song.albumName ?: audioQuality ?: "Apple Moozic",
                artworkUrl = publicCoverUrl,
                startTimestampSec = calculatedStartTime / 1000L,
                endTimestampSec = (currentTime + adjustedRemainingDuration) / 1000L
            )
            return@runCatching
        }

        // Fallback if a raw legacy token was pasted manually
        val buttonsList = mutableListOf<Pair<String, String>>()
        if (button1Visible) {
            buttonsList.add(resolveVariables(button1Text.ifEmpty { DEFAULT_BUTTON_1 }, song) to watchUrl(song))
        }
        if (button2Visible) {
            buttonsList.add(resolveVariables(button2Text.ifEmpty { DEFAULT_BUTTON_2 }, song) to PROJECT_URL)
        }

        val type = when (activityType) {
            "playing" -> Type.PLAYING
            "watching" -> Type.WATCHING
            "competing" -> Type.COMPETING
            else -> Type.LISTENING
        }

        setActivity(
            name = activityName.ifEmpty { "Apple Moozic" },
            details = songTitleWithRate,
            state = song.artist,
            detailsUrl = watchUrl(song),
            largeImage = publicCoverUrl?.let(RpcImage::ExternalImage),
            smallImage = null,
            largeText = audioQuality,
            smallText = null,
            buttons = buttonsList.takeIf { it.isNotEmpty() },
            type = type,
            statusDisplayType = if (useDetails) StatusDisplayType.DETAILS else StatusDisplayType.STATE,
            since = currentTime,
            startTime = calculatedStartTime,
            endTime = currentTime + adjustedRemainingDuration,
            applicationId = DiscordApplication.ID,
            status = status,
        )
    }

    override suspend fun close() {
        DiscordNative.clearRichPresence()
        if (!token.startsWith(DiscordAuthManager.OAUTH_TOKEN_PREFIX)) {
            runCatching { super.close() }
        }
    }

    companion object {
        const val PROJECT_URL = "https://github.com/Swayam-source/Apple-Moozic"
        const val DEFAULT_BUTTON_1 = "Listen on YouTube Music"
        const val DEFAULT_BUTTON_2 = "Apple Moozic"
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
