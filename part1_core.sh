#!/usr/bin/env bash
set -e

echo "[1/5] Changing Android applicationId & signature to com.applemoozic.app..."
python3 - << 'PY'
from pathlib import Path

g_file = Path("app/build.gradle.kts")
g = g_file.read_text()
g = g.replace('applicationId = "com.music.bitchord"', 'applicationId = "com.applemoozic.app"')
g = g.replace('applicationId = "com.dev.bitchord"', 'applicationId = "com.applemoozic.app.dev"')
g = g.replace('"UPDATE_APK_PREFIX", "\\"BitChord-\\""', '"UPDATE_APK_PREFIX", "\\"AppleMoozic-\\""')
g_file.write_text(g)
PY

echo "[2/5] Fixing SpotifyLoginScreen.kt & PlaybackService.kt..."
python3 - << 'PY'
from pathlib import Path

# Fix SpotifyLoginScreen.kt missing browserUserAgent
sp = Path("app/src/main/java/com/music/bitchord/auth/SpotifyLoginScreen.kt")
if sp.exists():
    s = sp.read_text()
    s = s.replace(
        "browserUserAgent(settings.userAgentString)",
        'settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\\d+(\\.\\d+)*\\s*"""), "").trim()'
    )
    sp.write_text(s)

# Fix PlaybackService.kt Kizzy wakeUp reference
ps = Path("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
if ps.exists():
    lines = ps.read_text().splitlines()
    new_lines = []
    for line in lines:
        if "wakeUp" in line:
            new_lines.append("        // Kizzy wakeUp removed")
        else:
            new_lines.append(line)
    ps.write_text("\n".join(new_lines) + "\n")
PY

echo "[3/5] Writing DiscordAuthManager.kt, DiscordNative.kt & DiscordRPC.kt (Default: Apple Music)..."
cat << 'KOTLIN' > app/src/main/java/com/music/bitchord/data/discord/DiscordAuthManager.kt
package com.music.bitchord.data.discord

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DiscordAuthManager {
    private const val TAG = "DiscordAuthManager"
    private const val PREFS_NAME = "applemoozic_discord_sdk_secure"
    private const val KEY_APP_ID = "discord_application_id"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_ms"
    private const val KEY_PENDING_VERIFIER = "pending_code_verifier"
    private const val KEY_PENDING_STATE = "pending_oauth_state"

    private const val AUTHORIZE_URL = "https://discord.com/oauth2/authorize"
    private const val TOKEN_URL = "https://discord.com/api/oauth2/token"
    private const val USER_ME_URL = "https://discord.com/api/v10/users/@me"
    private const val SCOPES = "openid sdk.social_layer_presence"

    const val SDK_TOKEN_PREFIX = "sdk:"
    const val OAUTH_TOKEN_PREFIX = "oauth2:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val itunesCoverCache = ConcurrentHashMap<String, String>()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApplicationId(context: Context): String {
        val saved = prefs(context).getString(KEY_APP_ID, null)?.trim().orEmpty()
        if (saved.isNotEmpty()) return saved
        return BuildConfig.DISCORD_CLIENT_ID.trim()
    }

    fun saveApplicationId(context: Context, rawAppId: String): Boolean {
        val clean = rawAppId.trim()
        val numericId = clean.toLongOrNull() ?: return false
        if (numericId <= 0L) return false

        prefs(context).edit().putString(KEY_APP_ID, clean).apply()
        AppSettings.setDiscordToken("$SDK_TOKEN_PREFIX$clean")
        AppSettings.setDiscordRpcEnabled(true)
        AppSettings.setDiscordAccount("discord_sdk", "Discord App ($clean)", null)

        scope.launch {
            DiscordNative.initialize(numericId)
        }
        return true
    }

    fun hasOAuthSession(context: Context): Boolean {
        return !prefs(context).getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()
    }

    fun launchOneTapAuthorize(context: Context) {
        val appId = getApplicationId(context)
        val verifier = generateRandomUrlSafe(48)
        val challenge = sha256UrlSafe(verifier)
        val state = generateRandomUrlSafe(24)

        prefs(context).edit()
            .putString(KEY_PENDING_VERIFIER, verifier)
            .putString(KEY_PENDING_STATE, state)
            .apply()

        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", appId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.DISCORD_REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "consent")
            .build()

        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(0xFF09090B.toInt())
            .setNavigationBarColor(0xFF09090B.toInt())
            .build()

        val customTabs = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(true)
            .build()

        if (context !is Activity) {
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        customTabs.launchUrl(context, uri)
    }

    fun consumeRedirectIntent(context: Context, intent: Intent?) {
        val uri = intent?.data ?: return
        if ((uri.scheme == "applemoozic" || uri.scheme == "bitchord") &&
            uri.host == "discord" &&
            uri.path?.startsWith("/callback") == true
        ) {
            intent.data = null
            scope.launch {
                exchangeCodeForTokens(context.applicationContext, uri)
            }
        }
    }

    private suspend fun exchangeCodeForTokens(context: Context, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val code = uri.getQueryParameter("code") ?: return@withContext false
            val returnedState = uri.getQueryParameter("state")
            val sp = prefs(context)
            val expectedState = sp.getString(KEY_PENDING_STATE, null)
            val verifier = sp.getString(KEY_PENDING_VERIFIER, null) ?: return@withContext false
            val appId = getApplicationId(context)

            if (!expectedState.isNullOrBlank() && returnedState != expectedState) {
                Log.e(TAG, "OAuth2 PKCE state mismatch")
                return@withContext false
            }

            val body = FormBody.Builder()
                .add("client_id", appId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", BuildConfig.DISCORD_REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val req = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .header("Accept", "application/json")
                .build()

            try {
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "OAuth2 token exchange failed HTTP ${resp.code}: $raw")
                        return@use false
                    }
                    val json = JSONObject(raw)
                    val accessToken = json.optString("access_token", "")
                    if (accessToken.isBlank()) return@use false
                    val refreshToken = json.optString("refresh_token", "")
                    val expiresIn = json.optLong("expires_in", 604800L)

                    sp.edit()
                        .putString(KEY_ACCESS_TOKEN, accessToken)
                        .putString(KEY_REFRESH_TOKEN, refreshToken)
                        .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
                        .remove(KEY_PENDING_VERIFIER)
                        .remove(KEY_PENDING_STATE)
                        .apply()

                    DiscordNative.initialize(appId.toLongOrNull())
                    DiscordNative.updateToken(accessToken)
                    AppSettings.setDiscordToken("$OAUTH_TOKEN_PREFIX$accessToken")
                    AppSettings.setDiscordRpcEnabled(true)
                    syncUserProfile(context, accessToken)
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "OAuth2 code exchange exception", e)
                false
            }
        }

    suspend fun getValidAccessToken(context: Context): String? = refreshMutex.withLock {
        val sp = prefs(context)
        val access = sp.getString(KEY_ACCESS_TOKEN, null) ?: return@withLock null
        val refresh = sp.getString(KEY_REFRESH_TOKEN, "") ?: ""
        val expiresAt = sp.getLong(KEY_EXPIRES_AT, 0L)
        val appId = getApplicationId(context)

        if (System.currentTimeMillis() + 60_000L < expiresAt) {
            return@withLock access
        }
        if (refresh.isBlank()) return@withLock null

        val body = FormBody.Builder()
            .add("client_id", appId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .build()

        val req = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("Accept", "application/json")
            .build()

        return@withLock try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val newAccess = json.optString("access_token", "")
                if (newAccess.isBlank()) return@use null
                val newRefresh = json.optString("refresh_token", refresh)
                val expiresIn = json.optLong("expires_in", 604800L)

                sp.edit()
                    .putString(KEY_ACCESS_TOKEN, newAccess)
                    .putString(KEY_REFRESH_TOKEN, newRefresh)
                    .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
                    .apply()

                DiscordNative.updateToken(newAccess)
                AppSettings.setDiscordToken("$OAUTH_TOKEN_PREFIX$newAccess")
                newAccess
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun syncUserProfile(context: Context, explicitToken: String? = null) =
        withContext(Dispatchers.IO) {
            val token = explicitToken ?: getValidAccessToken(context) ?: return@withContext
            val req = Request.Builder()
                .url(USER_ME_URL)
                .get()
                .header("Authorization", "Bearer $token")
                .build()

            runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val id = obj.optString("id", "")
                    val username = obj.optString("username", "")
                    val globalName = obj.optString("global_name", "").ifBlank { username }
                    val avatarHash = obj.optString("avatar", "")
                    val avatarUrl = if (id.isNotBlank() && avatarHash.isNotBlank()) {
                        "https://cdn.discordapp.com/avatars/$id/$avatarHash.png?size=256"
                    } else {
                        null
                    }
                    if (username.isNotBlank()) {
                        AppSettings.setDiscordAccount(username, globalName, avatarUrl)
                    }
                }
            }
        }

    suspend fun resolveItunesArtwork(title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            val key = "${artist.lowercase()}|${title.lowercase()}"
            itunesCoverCache[key]?.let { return@withContext it }

            val term = "$title $artist".trim()
            val url = Uri.parse("https://itunes.apple.com/search").buildUpon()
                .appendQueryParameter("term", term)
                .appendQueryParameter("media", "music")
                .appendQueryParameter("entity", "song")
                .appendQueryParameter("limit", "1")
                .build()
                .toString()

            val req = Request.Builder().url(url).get().build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val results = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results")
                        ?: return@use null
                    if (results.length() == 0) return@use null
                    val art = results.optJSONObject(0)?.optString("artworkUrl100", "").orEmpty()
                    if (art.isBlank()) return@use null
                    val hiRes = art.replace("100x100bb", "600x600bb")
                    itunesCoverCache[key] = hiRes
                    hiRes
                }
            } catch (_: Exception) {
                null
            }
        }

    fun disconnect(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
        scope.launch {
            DiscordNative.clearRichPresence()
        }
    }

    private fun generateRandomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256UrlSafe(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
KOTLIN

cat << 'KOTLIN' > app/src/main/java/com/music/bitchord/data/discord/DiscordNative.kt
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
    private var activeApplicationId = 0L
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

    suspend fun initialize(explicitAppId: Long? = null): Boolean = withContext(sdkDispatcher) {
        if (!nativeLibAvailable) return@withContext false

        val appId = explicitAppId
            ?.takeIf { it > 0L }
            ?: BuildConfig.DISCORD_CLIENT_ID.trim().toLongOrNull()
            ?: 0L

        if (appId <= 0L) return@withContext false
        if (activeApplicationId == appId) return@withContext true

        val ok = nativeInit(appId)
        if (ok) {
            activeApplicationId = appId
            startCallbackPump()
        }
        ok
    }

    suspend fun updateToken(accessToken: String): Boolean = withContext(sdkDispatcher) {
        if (!nativeLibAvailable) return@withContext false
        if (activeApplicationId <= 0L) initialize()
        nativeUpdateToken(accessToken)
    }

    suspend fun updateRichPresence(
        activityName: String,
        activityType: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        startTimestampSec: Long,
        endTimestampSec: Long
    ): Boolean = withContext(sdkDispatcher) {
        if (!nativeLibAvailable || activeApplicationId <= 0L) {
            _isPresenceLive.value = false
            return@withContext false
        }
        val cover = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: ""
        val updated = nativeUpdateRichPresence(
            activityName = activityName.ifBlank { "Apple Music" },
            activityType = activityType.ifBlank { "listening" },
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
        if (nativeLibAvailable && activeApplicationId > 0L) {
            nativeClearRichPresence()
        }
        _isPresenceLive.value = false
    }

    private fun startCallbackPump() {
        if (callbackPumpJob?.isActive == true) return
        callbackPumpJob = sdkScope.launch {
            while (isActive) {
                if (nativeLibAvailable && activeApplicationId > 0L) {
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
        activityName: String,
        activityType: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String,
        startTimestampSec: Long,
        endTimestampSec: Long
    ): Boolean
    private external fun nativeClearRichPresence()
}
KOTLIN

cat << 'KOTLIN' > app/src/main/java/com/music/bitchord/data/discord/DiscordRPC.kt
package com.music.bitchord.data.discord

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.youTubeThumbnailFallback
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
        const val PROJECT_URL = "https://github.com/Swayam-source/Apple-Moozic"
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
KOTLIN

echo "[4/5] Writing DiscordScreen.kt (no Kizzy, defaults to Apple Music)..."
cat << 'KOTLIN' > app/src/main/java/com/music/bitchord/ui/screens/DiscordScreen.kt
package com.music.bitchord.ui.screens

import com.music.bitchord.ui.icons.SfIcons
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.music.bitchord.ui.components.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.discord.DiscordAuthManager
import com.music.bitchord.data.discord.DiscordNative
import com.music.bitchord.data.discord.DiscordRPC
import com.music.bitchord.data.discord.discordAudioQualityLine
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.ChoiceAlert
import com.music.bitchord.ui.components.TextValueAlert
import com.music.bitchord.ui.components.thumbnailBorder
import dev.chrisbanes.haze.HazeState
import java.util.Locale

enum class DiscordPresenceStatus(val value: String, val label: String, val detail: String) {
    ONLINE("online", "Online", "Green dot"),
    IDLE("idle", "Idle", "Amber crescent"),
    DND("dnd", "Do not disturb", "Red dash"),
}

enum class DiscordActivityKind(val value: String, val verb: String, val label: String) {
    LISTENING("listening", "Listening to", "Listening"),
    PLAYING("playing", "Playing", "Playing"),
    WATCHING("watching", "Watching", "Watching"),
    COMPETING("competing", "Competing in", "Competing"),
}

enum class DiscordDialog { TOKEN, STATUS, ACTIVITY_TYPE, ACTIVITY_NAME, BUTTON_1, BUTTON_2 }

private fun statusOf(value: String) =
    DiscordPresenceStatus.entries.firstOrNull { it.value == value } ?: DiscordPresenceStatus.ONLINE

private fun kindOf(value: String) =
    DiscordActivityKind.entries.firstOrNull { it.value == value } ?: DiscordActivityKind.LISTENING

@Composable
fun DiscordScreen(
    song: Song?,
    positionMs: Long,
    durationMs: Long,
    onOpenLogin: () -> Unit,
    onOpenDialog: (DiscordDialog) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val token by AppSettings.discordToken.collectAsStateWithLifecycle()
    val username by AppSettings.discordUsername.collectAsStateWithLifecycle()
    val name by AppSettings.discordName.collectAsStateWithLifecycle()
    val avatar by AppSettings.discordAvatar.collectAsStateWithLifecycle()
    val rpcEnabled by AppSettings.discordRpcEnabled.collectAsStateWithLifecycle()
    val useDetails by AppSettings.discordUseDetails.collectAsStateWithLifecycle()
    val showAudioQuality by AppSettings.discordShowAudioQuality.collectAsStateWithLifecycle()
    val status by AppSettings.discordStatus.collectAsStateWithLifecycle()
    val activityType by AppSettings.discordActivityType.collectAsStateWithLifecycle()
    val activityName by AppSettings.discordActivityName.collectAsStateWithLifecycle()
    val button1Text by AppSettings.discordButton1Text.collectAsStateWithLifecycle()
    val button1Visible by AppSettings.discordButton1Visible.collectAsStateWithLifecycle()
    val button2Text by AppSettings.discordButton2Text.collectAsStateWithLifecycle()
    val button2Visible by AppSettings.discordButton2Visible.collectAsStateWithLifecycle()
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()

    val savedAppId = remember(token) { DiscordAuthManager.getApplicationId(context) }
    val hasValidAppId = savedAppId.toLongOrNull()?.let { it > 0L } == true
    val oauthAuthorized = remember(token) { DiscordAuthManager.hasOAuthSession(context) }
    val connected = token.isNotEmpty() || hasValidAppId
    val sdkBinaryReady = remember { DiscordNative.isSdkBinaryLoaded() }
    val discordIcon = ImageVector.vectorResource(R.drawable.ic_discord)
    val effectiveAppName = activityName.ifEmpty { DiscordRPC.DEFAULT_APP_NAME }

    LaunchedEffect(token) {
        if (DiscordAuthManager.hasOAuthSession(context)) {
            DiscordAuthManager.syncUserProfile(context)
        } else if (hasValidAppId && token.isEmpty()) {
            DiscordAuthManager.saveApplicationId(context, savedAppId)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.discord))

        DiscordAccountCard(
            connected = connected,
            name = name.ifEmpty { if (oauthAuthorized) "Discord Authorized" else "Discord App Linked" },
            username = username.ifEmpty { if (hasValidAppId) "App ID: $savedAppId" else "" },
            avatar = avatar,
            status = statusOf(status),
            icon = discordIcon,
            onConnect = onOpenLogin,
        )

        SettingsGroup(
            header = "Discord Application & OAuth2 Handshake",
            footer = if (sdkBinaryReady) {
                "Discord Social SDK is active. Tap 'Authorize Discord' to approve the OAuth2 handshake with your Discord account."
            } else {
                "Enter your Discord Developer Application ID and tap 'Authorize Discord' to approve the OAuth2 handshake."
            },
        ) {
            SettingsRow(
                icon = discordIcon,
                title = if (oauthAuthorized) "Re-Authorize Discord" else "Authorize Discord",
                subtitle = if (oauthAuthorized) {
                    "Authorized via OAuth2 (sdk.social_layer_presence)"
                } else {
                    "Directs you to Discord's official OAuth2 authorize prompt"
                },
                onClick = onOpenLogin,
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.Key,
                title = "Discord Application ID",
                subtitle = savedAppId.ifEmpty { "Tap to paste Application ID from Discord Developer Portal" },
                onClick = { onOpenDialog(DiscordDialog.TOKEN) },
            )
        }

        SettingsGroup(
            header = stringResource(R.string.rich_presence),
            footer = "Displays 'Listening to $effectiveAppName' with song artwork, title, artist, and live timestamps.",
        ) {
            SettingsRow(
                icon = discordIcon,
                title = stringResource(R.string.show_what_playing),
                subtitle = if (connected) null else "Authorize Discord or set an Application ID first",
                enabled = connected,
                trailing = {
                    IosSwitch(
                        checked = rpcEnabled && connected,
                        onCheckedChange = AppSettings::setDiscordRpcEnabled,
                        enabled = connected,
                    )
                },
                onClick = { AppSettings.setDiscordRpcEnabled(!rpcEnabled) },
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.Label,
                title = "Custom App Name (Listening to ...)",
                subtitle = effectiveAppName,
                enabled = connected && rpcEnabled,
                onClick = { onOpenDialog(DiscordDialog.ACTIVITY_NAME) },
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.Tune,
                title = stringResource(R.string.activity),
                value = kindOf(activityType).label,
                enabled = connected && rpcEnabled,
                onClick = { onOpenDialog(DiscordDialog.ACTIVITY_TYPE) },
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.GraphicEq,
                title = stringResource(R.string.discord_show_audio_quality),
                subtitle = stringResource(R.string.discord_show_audio_quality_subtitle),
                enabled = connected && rpcEnabled,
                trailing = {
                    IosSwitch(
                        checked = showAudioQuality,
                        onCheckedChange = AppSettings::setDiscordShowAudioQuality,
                        enabled = connected && rpcEnabled,
                    )
                },
                onClick = { AppSettings.setDiscordShowAudioQuality(!showAudioQuality) },
            )
        }

        SettingsGroup(
            header = stringResource(R.string.preview),
            footer = if (song == null) stringResource(R.string.play_to_preview) else null,
        ) {
            RichPresencePreview(
                song = song,
                positionMs = positionMs,
                durationMs = durationMs,
                heading = effectiveAppName,
                verb = kindOf(activityType).verb,
                useDetails = useDetails,
                audioQuality = discordAudioQualityLine(nerdStats).takeIf { showAudioQuality },
                button1Text = button1Text,
                button1Visible = button1Visible,
                button2Text = button2Text,
                button2Visible = button2Visible,
            )
        }

        if (connected) {
            SettingsGroup {
                DestructiveRow(
                    label = stringResource(R.string.disconnect),
                    onClick = {
                        DiscordAuthManager.disconnect(context)
                        AppSettings.clearDiscordAccount()
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiscordAccountCard(
    connected: Boolean,
    name: String,
    username: String,
    avatar: String,
    status: DiscordPresenceStatus,
    icon: ImageVector,
    onConnect: () -> Unit,
) {
    val cardColor = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(cardColor)
            .clickable(onClick = onConnect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            if (avatar.isNotEmpty()) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(CircleShape).thumbnailBorder(CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            if (connected) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .align(Alignment.BottomEnd)
                        .border(2.5.dp, cardColor, CircleShape)
                        .padding(2.5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (connected) name.ifEmpty { stringResource(R.string.connected) }
                else "Authorize Discord",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    username.isNotEmpty() -> username
                    connected -> "Discord Social SDK Ready"
                    else -> "Tap to authorize your Discord account via OAuth2"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
}

@Composable
private fun RichPresencePreview(
    song: Song?,
    positionMs: Long,
    durationMs: Long,
    heading: String,
    verb: String,
    useDetails: Boolean,
    audioQuality: String?,
    button1Text: String,
    button1Visible: Boolean,
    button2Text: String,
    button2Visible: Boolean,
) {
    val context = LocalContext.current
    val title = song?.title ?: stringResource(R.string.widget_preview_title)
    val artist = song?.artist ?: stringResource(R.string.widget_preview_artist)

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "$verb $heading".uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.Top) {
            val artShape = RoundedCornerShape(6.dp)
            if (song?.thumbnailUrl != null) {
                AsyncImage(
                    model = song.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(artShape).thumbnailBorder(artShape),
                )
            } else {
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(artShape)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    fontWeight = if (useDetails) FontWeight.W700 else FontWeight.W400,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    fontWeight = if (useDetails) FontWeight.W400 else FontWeight.W700,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                audioQuality?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ProgressLine(positionMs = positionMs, durationMs = durationMs)
            }
        }
    }
}

@Composable
private fun ProgressLine(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatClock(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatClock(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.ROOT, total / 60, total % 60)
}

@Composable
fun DiscordDialogHost(
    which: DiscordDialog,
    hazeState: HazeState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    when (which) {
        DiscordDialog.TOKEN -> {
            val currentAppId = remember { DiscordAuthManager.getApplicationId(context) }
            var input by remember { mutableStateOf(currentAppId) }
            TextValueAlert(
                hazeState = hazeState,
                title = "Discord Application ID",
                message = "Paste your Application ID (Client ID) from discord.com/developers/applications.",
                placeholder = "1350000000000000000",
                value = input,
                onValueChange = { input = it },
                onSave = {
                    if (DiscordAuthManager.saveApplicationId(context, input)) {
                        onDismiss()
                    }
                },
                onDismiss = onDismiss,
            )
        }

        DiscordDialog.STATUS -> {
            val current by AppSettings.discordStatus.collectAsStateWithLifecycle()
            ChoiceAlert(
                hazeState = hazeState,
                title = stringResource(R.string.status),
                message = stringResource(R.string.status_dialog_message),
                options = DiscordPresenceStatus.entries,
                selected = statusOf(current),
                label = { it.label },
                detail = { it.detail },
                onSelect = {
                    AppSettings.setDiscordStatus(it.value)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        DiscordDialog.ACTIVITY_TYPE -> {
            val current by AppSettings.discordActivityType.collectAsStateWithLifecycle()
            val name = AppSettings.discordActivityName.value.ifEmpty { DiscordRPC.DEFAULT_APP_NAME }
            ChoiceAlert(
                hazeState = hazeState,
                title = stringResource(R.string.activity),
                message = stringResource(R.string.activity_dialog_message),
                options = DiscordActivityKind.entries,
                selected = kindOf(current),
                label = { it.label },
                detail = { "\"${it.verb} $name\"" },
                onSelect = {
                    AppSettings.setDiscordActivityType(it.value)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        DiscordDialog.ACTIVITY_NAME -> {
            val current by AppSettings.discordActivityName.collectAsStateWithLifecycle()
            var input by remember { mutableStateOf(current.ifEmpty { DiscordRPC.DEFAULT_APP_NAME }) }
            TextValueAlert(
                hazeState = hazeState,
                title = "Custom App Name",
                message = "Sets what appears after 'Listening to' on your Discord profile (default: Apple Music).",
                placeholder = DiscordRPC.DEFAULT_APP_NAME,
                value = input,
                onValueChange = { input = it },
                onSave = {
                    AppSettings.setDiscordActivityName(input.trim())
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        DiscordDialog.BUTTON_1, DiscordDialog.BUTTON_2 -> {
            val first = which == DiscordDialog.BUTTON_1
            val flow = if (first) AppSettings.discordButton1Text else AppSettings.discordButton2Text
            val current by flow.collectAsStateWithLifecycle()
            var input by remember { mutableStateOf(current) }
            TextValueAlert(
                hazeState = hazeState,
                title = if (first) stringResource(R.string.first_button)
                else stringResource(R.string.second_button),
                message = stringResource(R.string.discord_button_variables),
                placeholder = if (first) DiscordRPC.DEFAULT_BUTTON_1 else DiscordRPC.DEFAULT_BUTTON_2,
                value = input,
                onValueChange = { input = it },
                onSave = {
                    if (first) {
                        AppSettings.setDiscordButton1Text(input.trim())
                    } else {
                        AppSettings.setDiscordButton2Text(input.trim())
                    }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}
KOTLIN

echo "[5/5] Part 1 complete!"