package com.music.bitchord.playback.cast

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

object WebCastServer {
    private const val TAG = "ZenWebCast"
    private const val DEFAULT_PORT = 8765

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

    @Volatile
    var snapshotProvider: (() -> TrackSnapshot)? = null

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var appContext: Context? = null

    @Volatile
    private var lastClientPingMs: Long = 0L

    fun start(context: Context) {
        appContext = context.applicationContext
        if (serverJob?.isActive == true) {
            refreshUrl()
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

    private fun refreshUrl() {
        val port = serverSocket?.localPort ?: return
        val ip = localWifiIp() ?: return
        val connected = SystemClock.elapsedRealtime() - lastClientPingMs < 5_000L
        _state.value = UiState(running = true, url = "http://$ip:$port", clientConnected = connected)
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
            val out = BufferedOutputStream(s.getOutputStream())
            when (path) {
                "/", "/index.html" -> serveReceiverHtml(out)
                "/api/state" -> serveStateJson(out)
                "/api/artwork" -> serveLocalArtwork(out)
                else -> writeBytes(out, "404 Not Found", "text/plain", ByteArray(0))
            }
            out.flush()
        }
    }

    private fun serveReceiverHtml(out: BufferedOutputStream) {
        val ctx = appContext ?: return
        val html = runCatching {
            ctx.assets.open("cast/index.html").use { it.readBytes() }
        }.getOrElse { "<h1>Zen Cast Receiver missing</h1>".toByteArray() }
        writeBytes(out, "200 OK", "text/html; charset=utf-8", html)
    }

    private fun serveStateJson(out: BufferedOutputStream) {
        lastClientPingMs = SystemClock.elapsedRealtime()
        val cur = _state.value
        if (cur.running && !cur.clientConnected) {
            _state.value = cur.copy(clientConnected = true)
        }
        val snap = snapshotProvider?.invoke() ?: TrackSnapshot()
        val artUrl = when (snap.artworkUri?.scheme) {
            "http", "https" -> snap.artworkUri.toString()
            "content", "file" -> "/api/artwork?t=${snap.trackId.hashCode()}"
            else -> ""
        }
        val lyricsArr = JSONArray()
        for (line in snap.lyrics) {
            lyricsArr.put(JSONObject().put("timeMs", line.timeMs).put("text", line.text))
        }
        val json = JSONObject()
            .put("trackId", snap.trackId)
            .put("title", snap.title)
            .put("artist", snap.artist)
            .put("artworkUrl", artUrl)
            .put("positionMs", snap.positionMs)
            .put("durationMs", snap.durationMs)
            .put("isPlaying", snap.isPlaying)
            .put("lyrics", lyricsArr)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        writeBytes(out, "200 OK", "application/json; charset=utf-8", json)
    }

    private fun serveLocalArtwork(out: BufferedOutputStream) {
        val ctx = appContext ?: return
        val uri = snapshotProvider?.invoke()?.artworkUri ?: return
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: ByteArray(0)
        writeBytes(out, if (bytes.isNotEmpty()) "200 OK" else "404 Not Found", "image/jpeg", bytes)
    }

    private fun writeBytes(out: BufferedOutputStream, status: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
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
