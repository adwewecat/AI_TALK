package com.adwewecat.nana.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adwewecat.nana.data.local.toDomain
import com.adwewecat.nana.di.AppContainer
import com.adwewecat.nana.syncOutcomeMessage
import com.adwewecat.nana.ui.home.CompanionHomeScreen
import com.adwewecat.nana.ui.memory.MemoryHistoryScreen
import com.adwewecat.nana.ui.recordings.CallRecordingsScreen
import com.adwewecat.nana.ui.settings.SettingsScreen
import com.adwewecat.nana.ui.theme.NaNaPalette
import com.adwewecat.nana.voice.NaNaVoiceService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

private enum class NaNaDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "Na Na", Icons.Rounded.AutoAwesome),
    RECORDINGS("recordings", "Ghi âm", Icons.Rounded.PhoneInTalk),
    MEMORY("memory", "Ký ức", Icons.Rounded.Psychology),
    SETTINGS("settings", "Cài đặt", Icons.Rounded.Settings),
}

/** Root Compose shell hosting Na Na's 4 bottom-navigation destinations. */
@Composable
fun NaNaApp(
    container: AppContainer,
    snackbarMessages: SharedFlow<String>,
    resumeTick: Int,
    onToggleVoiceService: (Boolean) -> Unit,
    onPushToTalk: () -> Unit,
    onSignInDrive: () -> Unit,
    onPickRecordingsFolder: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestPhoneAndContactsPermission: () -> Unit,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val voiceState by container.voiceOrchestrator.uiState.collectAsState()
    val isServiceRunning by NaNaVoiceService.isRunning.collectAsState()
    val authStatus by container.authManager.authStateFlow.collectAsState()
    val settings by container.settingsFlow.collectAsState()
    val apiKeys by container.keysFlow.collectAsState()
    val isSyncing by container.isSyncing.collectAsState()
    val lastSyncOutcome by container.lastSyncOutcome.collectAsState()

    val messageEntities by container.database.messageDao().observeAll().collectAsState(initial = emptyList())
    val memoryEntities by container.database.memoryFactDao().observeAll().collectAsState(initial = emptyList())
    val recordingEntities by container.database.callRecordingDao().observeAll().collectAsState(initial = emptyList())

    val messages = remember(messageEntities) { messageEntities.map { it.toDomain() } }
    val memories = remember(memoryEntities) { memoryEntities.map { it.toDomain() } }
    val recordings = remember(recordingEntities) { recordingEntities.map { it.toDomain() } }

    LaunchedEffect(snackbarMessages) {
        snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val runWifiSyncWithFeedback: () -> Unit = {
        scope.launch {
            val outcome = container.runManualWifiSync()
            snackbarHostState.showSnackbar(syncOutcomeMessage(outcome))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = NaNaPalette.DeepPlum,
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                NaNaDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NaNaDestination.HOME.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(NaNaDestination.HOME.route) {
                CompanionHomeScreen(
                    voiceState = voiceState,
                    isServiceRunning = isServiceRunning,
                    isWakeModelAvailable = remember(resumeTick) { container.wakeWordEngine.isModelAvailable() },
                    authStatus = authStatus,
                    isSyncing = isSyncing,
                    lastSyncOutcome = lastSyncOutcome,
                    messages = messages,
                    onToggleService = onToggleVoiceService,
                    onPushToTalk = onPushToTalk,
                    onSendText = { text ->
                        scope.launch { container.sendTextChat(text) }
                    },
                    onSyncNow = runWifiSyncWithFeedback,
                    onSignInDrive = onSignInDrive,
                )
            }

            composable(NaNaDestination.RECORDINGS.route) {
                CallRecordingsScreen(
                    recordings = recordings,
                    folderUri = settings.originOsRecordingFolderUri,
                    aiSummaryEnabled = settings.aiCallSummaryEnabled,
                    isSyncing = isSyncing,
                    onPickOrScanFolder = {
                        if (settings.originOsRecordingFolderUri.isNullOrBlank()) {
                            onPickRecordingsFolder()
                        } else {
                            scope.launch {
                                val count = container.scanOriginOsRecordings()
                                snackbarHostState.showSnackbar(
                                    if (count > 0) {
                                        "Đã tìm thấy và xếp hàng $count file ghi âm mới."
                                    } else {
                                        "Chưa có file ghi âm mới trong thư mục Record/Call."
                                    },
                                )
                            }
                        }
                    },
                    onSyncNow = runWifiSyncWithFeedback,
                    onSummarizeRecording = { record ->
                        scope.launch {
                            val summary = container.summarizeCallRecording(record)
                            if (summary == null) {
                                snackbarHostState.showSnackbar("Không thể tóm tắt AI (kiểm tra key Gemini hoặc file âm thanh).")
                            }
                        }
                    },
                )
            }

            composable(NaNaDestination.MEMORY.route) {
                MemoryHistoryScreen(
                    memories = memories,
                    messages = messages,
                    onAddMemory = { category, key, value ->
                        scope.launch {
                            container.addManualMemoryFact(category, key, value)
                            snackbarHostState.showSnackbar("Đã lưu ký ức mới.")
                        }
                    },
                    onUpdateMemory = { updated ->
                        scope.launch {
                            container.updateMemoryFact(updated)
                            snackbarHostState.showSnackbar("Đã cập nhật ký ức.")
                        }
                    },
                    onDeleteMemory = { id ->
                        scope.launch {
                            container.deleteMemoryFact(id)
                            snackbarHostState.showSnackbar("Đã xóa ký ức.")
                        }
                    },
                )
            }

            composable(NaNaDestination.SETTINGS.route) {
                SettingsScreen(
                    apiKeys = apiKeys,
                    settings = settings,
                    quotaState = voiceState.quotaState,
                    authStatus = authStatus,
                    isSyncing = isSyncing,
                    resumeTick = resumeTick,
                    onSaveKey = { entry ->
                        container.saveApiKey(entry)
                        scope.launch { snackbarHostState.showSnackbar("Đã lưu API key an toàn trên máy.") }
                    },
                    onDeleteKey = { id ->
                        container.deleteApiKey(id)
                        scope.launch { snackbarHostState.showSnackbar("Đã xóa API key.") }
                    },
                    onTestKey = { entry -> container.testApiKey(entry) },
                    onReviveNaNa = {
                        container.reviveNaNaNow()
                        scope.launch { snackbarHostState.showSnackbar("Đã làm mới cooldown và hồi sức Na Na!") }
                    },
                    onUpdateSettings = { transform -> container.updateSettings(transform) },
                    onSignInDrive = onSignInDrive,
                    onRestoreDrive = {
                        scope.launch {
                            val outcome = container.runRestore(allowMetered = false)
                            snackbarHostState.showSnackbar(
                                if (outcome == com.adwewecat.nana.core.sync.SyncOutcome.COMPLETED) {
                                    "Đã khôi phục dữ liệu từ Google Drive (nếu có)."
                                } else {
                                    syncOutcomeMessage(outcome)
                                },
                            )
                        }
                    },
                    onSignOutDrive = {
                        container.authManager.signOut()
                        scope.launch { snackbarHostState.showSnackbar("Đã đăng xuất Google Drive.") }
                    },
                    onPickRecordingsFolder = onPickRecordingsFolder,
                    onRequestMicPermission = onRequestMicPermission,
                    onRequestPhoneAndContactsPermission = onRequestPhoneAndContactsPermission,
                )
            }
        }
    }
}
