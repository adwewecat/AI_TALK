package com.adwewecat.nana.ui.recordings

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.RecordingSource
import com.adwewecat.nana.core.model.SyncStatus
import com.adwewecat.nana.ui.theme.NaNaPalette
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tab 2 ("Ghi âm"): Call recordings list with local [MediaPlayer] play/stop toggle,
 * Wi-Fi sync status chips, optional AI summary expansion, SAF scan, and Wi-Fi-only sync actions.
 */
@Composable
fun CallRecordingsScreen(
    recordings: List<CallRecordingRecord>,
    folderUri: String?,
    aiSummaryEnabled: Boolean,
    isSyncing: Boolean,
    onPickOrScanFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onSummarizeRecording: (CallRecordingRecord) -> Unit,
) {
    val context = LocalContext.current
    var playingId by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var expandedSummaries by remember { mutableStateOf(setOf<String>()) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    fun togglePlayback(record: CallRecordingRecord) {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        playbackError = null

        if (playingId == record.id) {
            playingId = null
            return
        }

        val player = MediaPlayer()
        val configured = runCatching {
            val localFile = record.stagedPath?.takeIf { it.isNotBlank() }?.let(::File)
            when {
                localFile != null && localFile.exists() -> player.setDataSource(localFile.absolutePath)
                record.sourceUri.isNotBlank() -> player.setDataSource(context, Uri.parse(record.sourceUri))
                else -> error("Không có nguồn file âm thanh")
            }
            player.setOnPreparedListener { mp ->
                mp.start()
            }
            player.setOnCompletionListener { mp ->
                if (mediaPlayer === mp) {
                    playingId = null
                    mediaPlayer = null
                }
                runCatching { mp.release() }
            }
            player.setOnErrorListener { mp, _, _ ->
                if (mediaPlayer === mp) {
                    playingId = null
                    mediaPlayer = null
                    playbackError = "Không thể phát file ghi âm này."
                }
                runCatching { mp.release() }
                true
            }
            player.prepareAsync()
            true
        }.getOrDefault(false)

        if (configured) {
            mediaPlayer = player
            playingId = record.id
        } else {
            runCatching { player.release() }
            playingId = null
            playbackError = "File ghi âm tạm đã được dọn sau khi lên Drive hoặc không còn quyền truy cập."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Ghi âm cuộc gọi",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (folderUri.isNullOrBlank()) {
                "Chưa chọn thư mục Record/Call của OriginOS"
            } else {
                "Đã liên kết thư mục OriginOS Record/Call"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onPickOrScanFolder,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (folderUri.isNullOrBlank()) {
                        "Chọn thư mục Record/Call"
                    } else {
                        "Quét thư mục Record/Call"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Button(
                onClick = onSyncNow,
                enabled = !isSyncing,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isSyncing) "Đang đồng bộ..." else "Đồng bộ ngay (chỉ Wi-Fi)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        playbackError?.let { err ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.labelMedium,
                color = NaNaPalette.AmberCooldown,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (recordings.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhoneInTalk,
                                contentDescription = null,
                                tint = NaNaPalette.SakuraPink,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Chưa có bản ghi âm cuộc gọi nào",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Hãy cấp quyền thư mục Record/Call trên OriginOS 6 và bấm Quét để sao lưu tự động khi có Wi-Fi.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(recordings, key = { it.id }) { record ->
                    val isPlaying = playingId == record.id
                    val isExpanded = record.id in expandedSummaries
                    RecordingItemCard(
                        record = record,
                        isPlaying = isPlaying,
                        isSummaryExpanded = isExpanded,
                        aiSummaryEnabled = aiSummaryEnabled,
                        onTogglePlay = { togglePlayback(record) },
                        onToggleExpand = {
                            expandedSummaries = if (isExpanded) {
                                expandedSummaries - record.id
                            } else {
                                expandedSummaries + record.id
                            }
                        },
                        onSummarize = { onSummarizeRecording(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingItemCard(
    record: CallRecordingRecord,
    isPlaying: Boolean,
    isSummaryExpanded: Boolean,
    aiSummaryEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onToggleExpand: () -> Unit,
    onSummarize: () -> Unit,
) {
    val title = record.contactName.ifBlank { record.phoneNumber }.ifBlank { "Cuộc gọi không rõ" }
    val sourceLabel = if (record.source == RecordingSource.ORIGINOS_NATIVE) "OriginOS" else "Trong app"

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatRecordingDate(record.startedAtMs)} · $sourceLabel · ${formatBytes(record.fileSizeBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Dừng phát" else "Phát ghi âm",
                        tint = NaNaPalette.SakuraPink,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SyncStatusChip(status = record.syncStatus)

                if (!record.aiSummary.isNullOrBlank()) {
                    TextButton(onClick = onToggleExpand) {
                        Text(if (isSummaryExpanded) "Thu gọn tóm tắt" else "Xem tóm tắt AI")
                        Icon(
                            imageVector = if (isSummaryExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                        )
                    }
                } else if (aiSummaryEnabled) {
                    TextButton(onClick = onSummarize) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tóm tắt AI")
                    }
                }
            }

            if (isSummaryExpanded && !record.aiSummary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = NaNaPalette.DeepPlum,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = record.aiSummary.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStatusChip(status: SyncStatus) {
    val (label, bg, fg, icon) = when (status) {
        SyncStatus.PENDING -> Quadruple(
            "Lưu tạm trên máy",
            NaNaPalette.AmberContainer,
            NaNaPalette.AmberCooldown,
            Icons.Rounded.CloudUpload,
        )
        SyncStatus.UPLOADING -> Quadruple(
            "Đang đồng bộ Wi-Fi",
            NaNaPalette.LavenderContainer,
            NaNaPalette.LavenderViolet,
            Icons.Rounded.Wifi,
        )
        SyncStatus.SYNCED -> Quadruple(
            "Đã lên Drive",
            NaNaPalette.MintContainer,
            NaNaPalette.MintHealthy,
            Icons.Rounded.CloudDone,
        )
        SyncStatus.FAILED -> Quadruple(
            "Lưu tạm trên máy (chờ thử lại)",
            NaNaPalette.CoralContainer,
            NaNaPalette.CoralError,
            Icons.Rounded.CloudUpload,
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

private val RECORDING_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

private fun formatRecordingDate(epochMs: Long): String =
    runCatching { RECORDING_DATE_FORMATTER.format(Instant.ofEpochMilli(epochMs)) }.getOrDefault("")

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> "${bytes / 1_024L} KB"
    else -> "$bytes B"
}
