package com.music.bitchord.data.discord

import android.util.Log
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

object DiscordNative {
    private const val TAG = "DiscordNative"

    private val sdkDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiscordSocialSdkThread").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val sdkScope = CoroutineScope(SupervisorJob() + sdkDispatcher)

    private var nativeLibAvailable = false
    private var isInitialized = false
    private var callbackPumpJob: Job? = null

    private val _isPresenceLive = MutableStateFlow(false)
    val isPresenceLive: StateFlow<Boolean> = _isPresenceLive.asStateFlow()

    init {
        nativeLibAvailable = try {
            System.loadLibrary("bitchord_discord")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libbitchord_discord.so", t)
            false
        }
    }

    fun isSdkBinaryLoaded(): Boolean {
        if (!nativeLibAvailable) return false
        return runCatching { nativeIsSdkLoaded() }.getOrDefault(false)
    }

    suspend fun initialize(): Boolean = withContext(sdkDispatcher) {
        if (!nativeLibAvailable) return@withContext false
        if (isInitialized) return@withContext true

        val appId = BuildConfig.DISCORD_CLIENT_ID.trim().toLongOrNull() ?: 0L
        if (appId <= 0L) return@withContext false

        val ok = nativeInit(appId)
        if (ok) {
            isInitialized = true
            startCallbackPump()
        }
        ok
    }

    suspend fun updateToken(accessToken: String): Boolean = withContext(sdkDispatcher) {
        if (!nativeLibAvailable) return@withContext false
        if (!isInitialized) initialize()
        nativeUpdateToken(accessToken)
    }

    suspend fun updateRichPresence(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        startTimestampSec: Long,
        endTimestampSec: Long
    ): Boolean = withContext(sdkDispatcher) {
        if (!nativeLibAvailable || !isInitialized) {
            _isPresenceLive.value = false
            return@withContext false
        }
        val cover = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: ""
        val updated = nativeUpdateRichPresence(
            title = title,
            artist = artist,
            album = album,
            artworkUrl = cover,
            startTimestampSec = startTimestampSec,
            endTimestampSec = endTimestampSec
        )
        _isPresenceLive.value = updated
        updated
    }

    suspend fun clearRichPresence() = withContext(sdkDispatcher) {
        if (nativeLibAvailable && isInitialized) {
            nativeClearRichPresence()
        }
        _isPresenceLive.value = false
    }

    private fun startCallbackPump() {
        if (callbackPumpJob?.isActive == true) return
        callbackPumpJob = sdkScope.launch {
            while (isActive) {
                if (nativeLibAvailable && isInitialized) {
                    nativeRunCallbacks()
                }
                delay(50L)
            }
        }
    }

    private external fun nativeIsSdkLoaded(): Boolean
    private external fun nativeInit(applicationId: Long): Boolean
    private external fun nativeUpdateToken(accessToken: String): Boolean
    private external fun nativeRunCallbacks()
    private external fun nativeUpdateRichPresence(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String,
        startTimestampSec: Long,
        endTimestampSec: Long
    ): Boolean
    private external fun nativeClearRichPresence()
}
