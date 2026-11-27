package com.music.bitchord.playback.cast

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object WebCastServer {
    private const val TAG = "ZenWebCast"
    private const val DEFAULT_PORT = 8765
    private val RANGE_REGEX = Regex("""bytes=(\d+)-(\d*)""")

    data class LyricEntry(val timeMs: Long, val text: String)

    data class TrackSnapshot(
        val trackId: String = "",
        val title: String = "Ready to Cast",
        val artist: String = "Select a song in Zen",
        val artworkUri: Uri? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
        val lyrics: List<LyricEntry> = emptyList(),
    )

    data class UiState(
        val running: Boolean = false,
        val url: String? = null,
        val clientConnected: Boolean = false,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val localStreams = ConcurrentHashMap<String, Uri>()

    @Volatile var snapshotProvider: (() -> TrackSnapshot)? = null
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var appContext: Context? = null
    @Volatile private var lastClientPingMs: Long = 0L

    suspend fun registerLocalStream(context: Context, trackId: String, uri: Uri): String? {
        if (serverJob?.isActive != true) start(context)
        var port = serverSocket?.localPort
        var tries = 0
        while (port == null && tries++ < 25) {
            delay(20L)
            port = serverSocket?.localPort
        }
        val activePort = port ?: return null
        val ip = localWifiIp() ?: return null
        val key = trackId.ifBlank { uri.toString().hashCode().toString() }
        localStreams[key] = uri
        return "http://$ip:$activePort/api/stream?id=${URLEncoder.encode(key, "UTF-8")}"
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (serverJob?.isActive == true) {
            val p = serverSocket?.localPort ?: return
            val ip = localWifiIp() ?: return
            val connected = SystemClock.elapsedRealtime() - lastClientPingMs < 5_000L
            _state.value = UiState(running = true, url = "http://$ip:$p", clientConnected = connected)
            return
        }
        serverJob = scope.launch {
            try {
                val socket = runCatching { ServerSocket(DEFAULT_PORT) }.getOrElse { ServerSocket(0) }
                serverSocket = socket
                val ip = localWifiIp() ?: "127.0.0.1"
                _state.value = UiState(running = true, url = "http://$ip:${socket.localPort}", clientConnected = false)
                while (isActive && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { handleClient(client) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "WebCast server stopped", t)
            } finally {
                _state.value = UiState()
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        lastClientPingMs = 0L
        _state.value = UiState()
    }

    private fun handleClient(socket: Socket) {
        runCatching {
            socket.use { s ->
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val sep = line.indexOf(':')
                    if (sep > 0) headers[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val rawTarget = parts.getOrNull(1) ?: "/"
                val path = rawTarget.substringBefore("?")
                val query = rawTarget.substringAfter("?", "")
                val out = BufferedOutputStream(s.getOutputStream())
                when (path) {
                    "/", "/index.html" -> serveReceiverHtml(out)
                    "/api/state" -> serveStateJson(out)
                    "/api/artwork" -> serveLocalArtwork(out)
                    "/api/stream" -> {
                        val rawId = query.split("&").firstOrNull { it.startsWith("id=") }?.removePrefix("id=").orEmpty()
                        serveLocalAudio(out, method, URLDecoder.decode(rawId, "UTF-8"), headers["range"])
                    }
                    else -> writeHeaders(out, "404 Not Found", "text/plain", 0L)
                }
                out.flush()
            }
        }
    }

    private fun serveReceiverHtml(out: BufferedOutputStream) {
        val ctx = appContext ?: return
        val html = runCatching { ctx.assets.open("cast/index.html").use { it.readBytes() } }
            .getOrElse { "<h1>Zen Cast Receiver missing</h1>".toByteArray() }
        writeHeaders(out, "200 OK", "text/html; charset=utf-8", html.size.toLong())
        out.write(html)
    }

    private fun serveStateJson(out: BufferedOutputStream) {
        lastClientPingMs = SystemClock.elapsedRealtime()
        val cur = _state.value
        if (cur.running && !cur.clientConnected) _state.value = cur.copy(clientConnected = true)
        val snap = snapshotProvider?.invoke() ?: TrackSnapshot()
        val artUrl = when (snap.artworkUri?.scheme) {
            "http", "https" -> snap.artworkUri.toString()
            "content", "file" -> "/api/artwork?t=${snap.trackId.hashCode()}"
            else -> ""
        }
        val lyricsArr = JSONArray().apply {
            snap.lyrics.forEach { put(JSONObject().put("timeMs", it.timeMs).put("text", it.text)) }
        }
        val json = JSONObject()
            .put("trackId", snap.trackId).put("title", snap.title).put("artist", snap.artist)
            .put("artworkUrl", artUrl).put("positionMs", snap.positionMs)
            .put("durationMs", snap.durationMs).put("isPlaying", snap.isPlaying)
            .put("lyrics", lyricsArr).toString().toByteArray(StandardCharsets.UTF_8)
        writeHeaders(out, "200 OK", "application/json; charset=utf-8", json.size.toLong())
        out.write(json)
    }

    private fun serveLocalArtwork(out: BufferedOutputStream) {
        val ctx = appContext ?: return
        val uri = snapshotProvider?.invoke()?.artworkUri ?: return
        val bytes = runCatching {
            if (uri.scheme == "file" && uri.path != null) File(uri.path!!).readBytes()
            else ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: ByteArray(0)
        writeHeaders(out, if (bytes.isNotEmpty()) "200 OK" else "404 Not Found", "image/jpeg", bytes.size.toLong())
        if (bytes.isNotEmpty()) out.write(bytes)
    }

    private fun serveLocalAudio(out: BufferedOutputStream, method: String, streamId: String, rangeHeader: String?) {
        val ctx = appContext ?: return
        val uri = localStreams[streamId] ?: run { writeHeaders(out, "404 Not Found", "text/plain", 0L); return }
        val afd = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull() ?: run {
            writeHeaders(out, "404 Not Found", "text/plain", 0L); return
        }
        afd.use { descriptor ->
            val mime = ctx.contentResolver.getType(uri) ?: CastPlayback.mimeTypeOf(uri)
            val total = descriptor.length.takeIf { it >= 0L }
                ?: uri.path?.takeIf { uri.scheme == "file" }?.let { File(it).length().takeIf { l -> l > 0L } }
                ?: -1L
            if (total <= 0L) { writeHeaders(out, "404 Not Found", "text/plain", 0L); return }
            val match = rangeHeader?.let { RANGE_REGEX.find(it) }
            val start = match?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0L, total - 1L) ?: 0L
            val end = match?.groupValues?.get(2)?.toLongOrNull()?.coerceIn(start, total - 1L) ?: (total - 1L)
            val length = end - start + 1L
            if (match != null) {
                writeHeaders(out, "206 Partial Content", mime, length, "Content-Range: bytes $start-$end/$total\r\nAccept-Ranges: bytes\r\n")
            } else {
                writeHeaders(out, "200 OK", mime, total, "Accept-Ranges: bytes\r\n")
            }
            if (method == "HEAD") return
            descriptor.createInputStream().use { input ->
                if (start > 0L) {
                    if (input is FileInputStream) input.channel.position(start)
                    else {
                        var skipped = 0L
                        while (skipped < start) {
                            val s = input.skip(start - skipped)
                            if (s <= 0L) break
                            skipped += s
                        }
                    }
                }
                val buf = ByteArray(65_536)
                var remaining = length
                while (remaining > 0L) {
                    val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun writeHeaders(out: BufferedOutputStream, status: String, contentType: String, contentLength: Long, extra: String = "") {
        val header = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: $contentLength\r\n" +
            extra + "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
    }

    private fun localWifiIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
