package com.music.zen.ui.screens

import com.music.zen.ui.icons.SfIcons
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
import com.music.zen.ui.components.IosSwitch
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
import com.music.zen.R
import com.music.zen.data.NerdStats
import com.music.zen.data.discord.DiscordAuthManager
import com.music.zen.data.discord.DiscordNative
import com.music.zen.data.discord.DiscordRPC
import com.music.zen.data.discord.discordAudioQualityLine
import com.music.zen.data.model.CARD_ART_PX
import com.music.zen.data.model.Song
import com.music.zen.data.model.artworkAt
import com.music.zen.data.settings.AppSettings
import com.music.zen.ui.components.ChoiceAlert
import com.music.zen.ui.components.TextValueAlert
import com.music.zen.ui.components.thumbnailBorder
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
