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
    private const val PREFS_NAME = "bitchord_discord_oauth2_secure"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_ms"
    private const val KEY_PENDING_VERIFIER = "pending_code_verifier"
    private const val KEY_PENDING_STATE = "pending_oauth_state"

    private const val AUTHORIZE_URL = "https://discord.com/oauth2/authorize"
    private const val TOKEN_URL = "https://discord.com/api/oauth2/token"
    private const val USER_ME_URL = "https://discord.com/api/v10/users/@me"
    private const val SCOPES = "openid sdk.social_layer_presence"

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

    fun launchOneTapAuthorize(context: Context) {
        val verifier = generateRandomUrlSafe(48)
        val challenge = sha256UrlSafe(verifier)
        val state = generateRandomUrlSafe(24)

        prefs(context).edit()
            .putString(KEY_PENDING_VERIFIER, verifier)
            .putString(KEY_PENDING_STATE, state)
            .apply()

        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.DISCORD_CLIENT_ID)
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

            if (!expectedState.isNullOrBlank() && returnedState != expectedState) {
                Log.e(TAG, "OAuth2 PKCE state mismatch")
                return@withContext false
            }

            val body = FormBody.Builder()
                .add("client_id", BuildConfig.DISCORD_CLIENT_ID)
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

                    DiscordNative.initialize()
                    DiscordNative.updateToken(accessToken)
                    AppSettings.setDiscordToken("$OAUTH_TOKEN_PREFIX$accessToken")
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

        if (System.currentTimeMillis() + 60_000L < expiresAt) {
            return@withLock access
        }
        if (refresh.isBlank()) return@withLock null

        val body = FormBody.Builder()
            .add("client_id", BuildConfig.DISCORD_CLIENT_ID)
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
