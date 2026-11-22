package com.adwewecat.nana.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.ui.theme.NaNaPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tab 3 ("Ký ức"): Searchable conversation history + editable [MemoryFact] cards
 * allowing manual additions, edits, and deletions.
 */
@Composable
fun MemoryHistoryScreen(
    memories: List<MemoryFact>,
    messages: List<ChatMessage>,
    onAddMemory: (MemoryCategory, String, String) -> Unit,
    onUpdateMemory: (MemoryFact) -> Unit,
    onDeleteMemory: (String) -> Unit,
) {
    var selectedSegment by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var editingFact by remember { mutableStateOf<MemoryFact?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val trimmedQuery = searchQuery.trim()
    val filteredMemories = remember(memories, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            memories
        } else {
            memories.filter { fact ->
                fact.key.contains(trimmedQuery, ignoreCase = true) ||
                    fact.value.contains(trimmedQuery, ignoreCase = true) ||
                    categoryLabel(fact.category).contains(trimmedQuery, ignoreCase = true)
            }
        }
    }

    val filteredMessages = remember(messages, trimmedQuery) {
        val ordered = messages.sortedByDescending { it.createdAtMs }
        if (trimmedQuery.isEmpty()) {
            ordered
        } else {
            ordered.filter { msg ->
                msg.content.contains(trimmedQuery, ignoreCase = true) ||
                    msg.role.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Ký ức & Lịch sử",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (selectedSegment == 0) {
                Button(onClick = { showAddDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Thêm ký ức")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val tabs = listOf("Ký ức (${memories.size})", "Lịch sử (${messages.size})")
            tabs.forEachIndexed { index, title ->
                SegmentedButton(
                    selected = selectedSegment == index,
                    onClick = { selectedSegment = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                ) {
                    Text(title, maxLines = 1)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(if (selectedSegment == 0) "Tìm sở thích, thói quen..." else "Tìm nội dung trò chuyện...")
            },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (selectedSegment == 0) {
                if (filteredMemories.isEmpty()) {
                    item {
                        EmptyMemoryCard(
                            title = "Chưa có ký ức phù hợp",
                            subtitle = "Na Na ghi nhớ sở thích, thói quen của anh tại đây. Anh có thể bấm \"Thêm ký ức\" bất cứ lúc nào.",
                        )
                    }
                } else {
                    items(filteredMemories, key = { it.id }) { fact ->
                        MemoryFactCard(
                            fact = fact,
                            onEdit = { editingFact = fact },
                            onDelete = { onDeleteMemory(fact.id) },
                        )
                    }
                }
            } else {
                if (filteredMessages.isEmpty()) {
                    item {
                        EmptyMemoryCard(
                            title = "Chưa có lịch sử trò chuyện",
                            subtitle = "Các cuộc trò chuyện với Na Na và Em gái sẽ được lưu offline trên máy và đồng bộ khi có Wi-Fi.",
                        )
                    }
                } else {
                    items(filteredMessages, key = { it.id }) { msg ->
                        HistoryMessageCard(message = msg)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        MemoryFactEditorDialog(
            initialFact = null,
            onDismiss = { showAddDialog = false },
            onSave = { category, key, value ->
                onAddMemory(category, key, value)
                showAddDialog = false
            },
        )
    }

    editingFact?.let { existing ->
        MemoryFactEditorDialog(
            initialFact = existing,
            onDismiss = { editingFact = null },
            onSave = { category, key, value ->
                onUpdateMemory(existing.copy(category = category, key = key, value = value))
                editingFact = null
            },
        )
    }
}

@Composable
private fun MemoryFactCard(
    fact: MemoryFact,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = NaNaPalette.SakuraContainer,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = categoryLabel(fact.category),
                            style = MaterialTheme.typography.labelMedium,
                            color = NaNaPalette.SakuraPink,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        text = if (fact.synced) "Đã lên Drive" else "Chờ Wi-Fi",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (fact.synced) NaNaPalette.MintHealthy else NaNaPalette.AmberCooldown,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = fact.key,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = fact.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Sửa ký ức",
                        tint = NaNaPalette.LavenderViolet,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Xóa ký ức",
                        tint = NaNaPalette.CoralError,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryMessageCard(message: ChatMessage) {
    val sender = when {
        message.role.equals("user", ignoreCase = true) -> "Anh"
        message.persona == PersonaMode.LITTLE_SISTER -> "Em gái Na Na"
        else -> "Na Na"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = sender,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NaNaPalette.SakuraPink,
                )
                Text(
                    text = formatDateTime(message.createdAtMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyMemoryCard(title: String, subtitle: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryFactEditorDialog(
    initialFact: MemoryFact?,
    onDismiss: () -> Unit,
    onSave: (MemoryCategory, String, String) -> Unit,
) {
    var selectedCategory by remember { mutableStateOf(initialFact?.category ?: MemoryCategory.PREFERENCE) }
    var keyText by remember { mutableStateOf(initialFact?.key.orEmpty()) }
    var valueText by remember { mutableStateOf(initialFact?.value.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialFact == null) "Thêm ký ức mới" else "Chỉnh sửa ký ức") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(categoryLabel(cat)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("Tên mục (ví dụ: đồ uống yêu thích)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text("Chi tiết") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedCategory, keyText.trim(), valueText.trim()) },
                enabled = keyText.isNotBlank() && valueText.isNotBlank(),
            ) {
                Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        },
    )
}

private fun categoryLabel(category: MemoryCategory): String = when (category) {
    MemoryCategory.PREFERENCE -> "Sở thích"
    MemoryCategory.HABIT -> "Thói quen"
    MemoryCategory.RELATIONSHIP -> "Quan hệ"
    MemoryCategory.SCHEDULE -> "Lịch trình"
    MemoryCategory.EMOTION -> "Cảm xúc"
    MemoryCategory.FACT -> "Thông tin"
}

private val HISTORY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"))

private fun formatDateTime(epochMs: Long): String =
    runCatching { HISTORY_DATE_FORMATTER.format(Instant.ofEpochMilli(epochMs)) }.getOrDefault("")
