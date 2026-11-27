package com.music.bitchord.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.music.bitchord.R
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.playback.cast.WebCastServer
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState

@Composable
fun CastDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cast by CastController.state.collectAsStateWithLifecycle()
    val webCast by WebCastServer.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var picked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { WebCastServer.start(context) }

    DisposableEffect(cast.supported) {
        val stop = CastController.discover(active = true)
        onDispose(stop)
    }
    LaunchedEffect(cast.connectedName, picked) {
        if (picked && cast.connectedName != null) onDismiss()
    }

    AudioPopupCard(
        hazeState = hazeState,
        title = stringResource(R.string.cast_dialog_title),
        subtitle = stringResource(R.string.cast_dialog_subtitle),
        doneLabel = stringResource(R.string.done),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            PipelineRule(Modifier)
            WebCastQrSection(
                url = webCast.url,
                connected = webCast.clientConnected,
                onStop = {
                    haptics.play(Haptic.Select)
                    WebCastServer.stop()
                    onDismiss()
                },
            )
            PipelineRule(Modifier)
            if (cast.devices.isNotEmpty()) {
                cast.devices.forEach { device ->
                    CastDeviceRow(
                        name = device.name,
                        connecting = device.connecting,
                        connected = cast.casting && device.connected,
                        onClick = {
                            if (!device.connected) {
                                haptics.play(Haptic.Select)
                                picked = true
                                CastController.connect(device.id)
                            }
                        },
                    )
                    PipelineRule(Modifier.padding(start = 56.dp))
                }
            }
            if (cast.casting) {
                CastActionRow(
                    label = stringResource(R.string.cast_stop),
                    onClick = {
                        haptics.play(Haptic.Select)
                        CastController.disconnect(resumeHere = true)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun WebCastQrSection(url: String?, connected: Boolean, onStop: () -> Unit) {
    val qrBitmap = remember(url) { url?.let { generateQrBitmap(it, 360) } }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("TV / Browser (Wi-Fi Cast)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.W600)
                Text(
                    text = if (connected) "Connected to TV screen" else "Scan QR or open link on any TV / laptop",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.5.sp,
                )
            }
            if (connected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (qrBitmap != null && url != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier.size(136.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(bitmap = qrBitmap, contentDescription = "Cast QR Code", modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(8.dp))
            Text(url, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.W600)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Stop TV Cast",
                color = Color(0xFFFF6B63),
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                modifier = Modifier.clickable(onClick = onStop).padding(vertical = 4.dp, horizontal = 10.dp),
            )
        }
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx) { i ->
        if (matrix[i % sizePx, i / sizePx]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

@Composable
private fun CastDeviceRow(name: String, connecting: Boolean, connected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (pressed) Color.White.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (connected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (connected) 1f else 0.8f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = if (connected) FontWeight.W600 else FontWeight.Normal),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            connecting -> CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White.copy(alpha = 0.8f), strokeWidth = 1.75.dp)
            connected -> Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CastActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.W600), color = Color(0xFFFF6B63))
    }
}
