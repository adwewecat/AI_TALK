package com.adwewecat.nana.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.sync.SyncOutcome
import com.adwewecat.nana.data.auth.DriveAuthStatus
import com.adwewecat.nana.ui.theme.NaNaPalette
import com.adwewecat.nana.voice.VoiceLoopState
import com.adwewecat.nana.voice.VoiceUiSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tab 1 ("Na Na"): Animated pulsing voice orb, persona & Wi-Fi sync pills,
 * 1-tap Drive reauth banner, always-listening switch, live chat bubbles,
 * text input fallback, and big Push-to-Talk FAB.
 */
@Composable
fun CompanionHomeScreen(
    voiceState: VoiceUiSnapshot,
    isServiceRunning: Boolean,
    isWakeModelAvailable: Boolean,
    authStatus: DriveAuthStatus,
    isSyncing: Boolean,
    lastSyncOutcome: SyncOutcome?,
    messages: List<ChatMessage>,
    onToggleService: (Boolean) -> Unit,
    onPushToTalk: () -> Unit,
    onSendText: (String) -> Unit,
    onSyncNow: () -> Unit,
    onSignInDrive: () -> Unit,
) {
    var draftText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val isLittleSister = voiceState.activePersona == PersonaMode.LITTLE_SISTER
    val accentColor = if (isLittleSister) NaNaPalette.LavenderViolet else NaNaPalette.SakuraPink

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Top status pills: Persona badge + Wi-Fi sync pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonaStatusPill(
                persona = voiceState.activePersona,
                quotaState = voiceState.quotaState,
            )

            WifiSyncPill(
                authStatus = authStatus,
                isSyncing = isSyncing,
                lastSyncOutcome = lastSyncOutcome,
                onClick = {
                    if (authStatus.isSignedIn) {
                        onSyncNow()
                    } else {
                        onSignInDrive()
                    }
                },
            )
        }

        if (authStatus.needsReauth) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = NaNaPalette.CoralContainer),
                border = BorderStroke(1.dp, NaNaPalette.CoralError.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = NaNaPalette.CoralError,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Phiên Google Drive cần xác thực lại",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onSignInDrive,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = NaNaPalette.CoralError,
                            contentColor = Color(0xFF3E0410),
                        ),
                    ) {
                        Text("Đăng nhập lại Google Drive", maxLines = 1)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Animated voice orb + always-listening switch card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VoiceCompanionOrb(
                    loopState = voiceState.loopState,
                    micLevel = voiceState.micLevel,
                    accentColor = accentColor,
                    onClick = onPushToTalk,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = loopStateLabel(voiceState.loopState, voiceState.activePersona),
                    style = MaterialTheme.typography.titleMedium,
                    color = accentColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = voiceState.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lắng nghe \"Na Na\" liên tục",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = if (isWakeModelAvailable) {
                                "Vosk offline sẵn sàng · Bật để gọi \"Na Na\""
                            } else {
                                "Chưa có model Vosk offline · Dùng nút Mic hoặc gõ chữ"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = onToggleService,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Live conversation bubbles
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Surface(
                        color = NaNaPalette.DeepPlum,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Chào anh nha~",
                                style = MaterialTheme.typography.titleMedium,
                                color = NaNaPalette.SakuraPink,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Anh bấm nút Mic lớn bên dưới để nói chuyện với Na Na hoặc gõ tin nhắn vào ô bên dưới nhé.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    ChatBubbleItem(message = msg)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom input fallback + Push-to-Talk FAB
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = draftText,
                onValueChange = { draftText = it },
                placeholder = { Text("Nhắn với Na Na...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val trimmed = draftText.trim()
                        if (trimmed.isNotEmpty()) {
                            onSendText(trimmed)
                            draftText = ""
                        }
                    },
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val trimmed = draftText.trim()
                            if (trimmed.isNotEmpty()) {
                                onSendText(trimmed)
                                draftText = ""
                            }
                        },
                        enabled = draftText.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Gửi tin nhắn",
                            tint = if (draftText.isNotBlank()) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )

            LargeFloatingActionButton(
                onClick = onPushToTalk,
                containerColor = accentColor,
                contentColor = Color(0xFF280517),
                shape = CircleShape,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = "Nhấn để nói với Na Na",
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceCompanionOrb(
    loopState: VoiceLoopState,
    micLevel: Float,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.09f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idleScale",
    )

    val activeBoost = when (loopState) {
        VoiceLoopState.LISTENING,
        VoiceLoopState.AWAITING_CONSENT,
        -> 1.06f + (micLevel.coerceIn(0f, 1f) * 0.28f)

        VoiceLoopState.SPEAKING -> 1.16f
        VoiceLoopState.THINKING -> 1.08f
        VoiceLoopState.IDLE_WAKE -> idlePulse
        VoiceLoopState.STOPPED -> 0.96f
    }

    val animatedScale by animateFloatAsState(
        targetValue = activeBoost,
        animationSpec = tween(durationMillis = 160),
        label = "orbScale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(124.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .scale(animatedScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.42f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    ),
                ),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            accentColor,
                            NaNaPalette.LavenderViolet,
                        ),
                    ),
                ),
        ) {
            Icon(
                imageVector = if (loopState == VoiceLoopState.SPEAKING || loopState == VoiceLoopState.LISTENING) {
                    Icons.Rounded.GraphicEq
                } else {
                    Icons.Rounded.AutoAwesome
                },
                contentDescription = "Na Na Voice Orb",
                tint = Color(0xFF280517),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun PersonaStatusPill(
    persona: PersonaMode,
    quotaState: QuotaState,
) {
    val (label, container, content) = when {
        persona == PersonaMode.LITTLE_SISTER -> Triple(
            "Em gái — Free",
            NaNaPalette.LavenderContainer,
            NaNaPalette.LavenderViolet,
        )

        quotaState == QuotaState.NANA_TIRED || quotaState == QuotaState.NANA_TIRED_AWAITING_CONSENT -> Triple(
            "Na Na (đang nghỉ)",
            NaNaPalette.AmberContainer,
            NaNaPalette.AmberCooldown,
        )

        else -> Triple(
            "Na Na",
            NaNaPalette.SakuraContainer,
            NaNaPalette.SakuraPink,
        )
    }

    Surface(
        color = container,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, content.copy(alpha = 0.4f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun WifiSyncPill(
    authStatus: DriveAuthStatus,
    isSyncing: Boolean,
    lastSyncOutcome: SyncOutcome?,
    onClick: () -> Unit,
) {
    val (icon, text, tint) = when {
        isSyncing -> Triple(Icons.Rounded.CloudSync, "Đang đồng bộ Wi-Fi...", NaNaPalette.SakuraPink)
        !authStatus.isOAuthConfigured -> Triple(Icons.Rounded.WifiOff, "Drive chưa cấu hình", NaNaPalette.TextSecondary)
        authStatus.needsReauth -> Triple(Icons.Rounded.Warning, "Cần đăng nhập Drive", NaNaPalette.CoralError)
        !authStatus.isSignedIn -> Triple(Icons.Rounded.WifiOff, "Chưa liên kết Drive", NaNaPalette.TextSecondary)
        lastSyncOutcome == SyncOutcome.PAUSED_NO_WIFI -> Triple(Icons.Rounded.WifiOff, "Chờ Wi-Fi", NaNaPalette.AmberCooldown)
        else -> Triple(Icons.Rounded.Wifi, "Đồng bộ chỉ Wi-Fi", NaNaPalette.MintHealthy)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        modifier = Modifier.clickable(enabled = !isSyncing, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChatBubbleItem(message: ChatMessage) {
    val isUser = message.role.equals("user", ignoreCase = true)
    val bubbleColor = if (isUser) {
        NaNaPalette.BubbleSlate
    } else if (message.persona == PersonaMode.LITTLE_SISTER) {
        NaNaPalette.LavenderContainer
    } else {
        NaNaPalette.SakuraContainer
    }
    val senderLabel = when {
        isUser -> "Anh"
        message.persona == PersonaMode.LITTLE_SISTER -> "Em gái Na Na"
        else -> "Na Na"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = senderLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isUser) NaNaPalette.TextSecondary else NaNaPalette.SakuraPink,
                    )
                    Text(
                        text = formatShortTime(message.createdAtMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun loopStateLabel(loopState: VoiceLoopState, persona: PersonaMode): String = when (loopState) {
    VoiceLoopState.STOPPED -> "Na Na đang tạm dừng"
    VoiceLoopState.IDLE_WAKE -> "Đang chờ gọi \"Na Na\""
    VoiceLoopState.LISTENING -> if (persona == PersonaMode.LITTLE_SISTER) {
        "Em gái đang lắng nghe..."
    } else {
        "Na Na đang lắng nghe..."
    }
    VoiceLoopState.THINKING -> "Đang suy nghĩ..."
    VoiceLoopState.SPEAKING -> "Đang nói..."
    VoiceLoopState.AWAITING_CONSENT -> "Chờ anh trả lời (Có / Không)..."
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"))

private fun formatShortTime(epochMs: Long): String =
    runCatching { TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs)) }.getOrDefault("")
