package com.music.bitchord.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class UpdateFileProvider : FileProvider()

data class ReleaseUpdate(
    val tagName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

object AppUpdater {
    private const val PREFS = "zen_in_app_updater"
    private const val GITHUB_REPO = "Swayam-source/Zen"

    private fun pkgUpdateTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    suspend fun checkLatest(context: Context): ReleaseUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val curTime = pkgUpdateTime(context)
            val pendingTime = prefs.getLong("pending_base_time", 0L)
            if (pendingTime in 1 until curTime) {
                prefs.edit()
                    .putString("installed_tag", prefs.getString("pending_tag", ""))
                    .remove("pending_base_time")
                    .apply()
            }

            val req = Request.Builder()
                .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                res.body?.string()
            } ?: return@withContext null

            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) return@withContext null

            val installedTag = prefs.getString("installed_tag", "").orEmpty()
            val dismissedTag = prefs.getString("dismissed_tag", "").orEmpty()
            val cleanTag = tag.removePrefix("v").removePrefix("V")
            val cleanCur = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V")
            if (tag == installedTag || tag == dismissedTag || cleanTag == cleanCur) {
                return@withContext null
            }

            val assets = json.optJSONArray("assets") ?: return@withContext null
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase()
            var chosenUrl = ""
            var chosenSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name").lowercase()
                val url = a.optString("browser_download_url")
                if (name.endsWith(".apk") && url.isNotEmpty()) {
                    if (chosenUrl.isEmpty() || (abi.isNotEmpty() && name.contains(abi)) || name.contains("universal")) {
                        chosenUrl = url
                        chosenSize = a.optLong("size", 0L)
                    }
                }
            }
            if (chosenUrl.isEmpty()) return@withContext null

            ReleaseUpdate(
                tagName = tag,
                title = json.optString("name").ifBlank { "Zen $tag" },
                notes = json.optString("body").trim(),
                apkUrl = chosenUrl,
                sizeBytes = chosenSize,
            )
        }.getOrNull()
    }

    fun dismiss(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("dismissed_tag", tag).apply()
    }

    suspend fun downloadApk(
        context: Context,
        update: ReleaseUpdate,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, "zen-update.apk")
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder().url(update.apkUrl).build()
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body ?: return@withContext null
                val total = body.contentLength().takeIf { it > 0L } ?: update.sizeBytes
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(32_768)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0L) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                        output.flush()
                    }
                }
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("pending_tag", update.tagName)
                .putLong("pending_base_time", pkgUpdateTime(context))
                .apply()
            outFile
        }.getOrNull()
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }
}

@Composable
fun AppUpdaterHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<ReleaseUpdate?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        update = AppUpdater.checkLatest(context)
    }

    val current = update ?: return

    Dialog(
        onDismissRequest = { if (!downloading) update = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(24.dp)),
            color = Color(0xFF181622),
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text("Update Available", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("${current.title} (${current.tagName})", color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp)

                if (current.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(current.notes, color = Color.White.copy(alpha = 0.85f), fontSize = 13.5.sp)
                    }
                }

                if (downloading) {
                    Spacer(Modifier.height(18.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Downloading update… ${(progress * 100).toInt()}%", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                }

                errorText?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color(0xFFFF6B63), fontSize = 13.sp)
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!downloading) {
                        Text(
                            text = "Later",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    AppUpdater.dismiss(context, current.tagName)
                                    update = null
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        val readyFile = downloadedFile
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .clickable {
                                    errorText = null
                                    if (readyFile != null && readyFile.exists()) {
                                        if (AppUpdater.installApk(context, readyFile)) update = null
                                        else errorText = "Allow 'Install unknown apps' for Zen, then tap Install again."
                                    } else {
                                        downloading = true
                                        progress = 0f
                                        scope.launch {
                                            val file = AppUpdater.downloadApk(context, current) { progress = it }
                                            downloading = false
                                            if (file != null) {
                                                downloadedFile = file
                                                if (AppUpdater.installApk(context, file)) update = null
                                                else errorText = "Allow 'Install unknown apps' for Zen, then tap Install."
                                            } else {
                                                errorText = "Download failed. Please try again."
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = if (readyFile != null) "Install" else "Update Now",
                                color = Color(0xFF0C0B10),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
