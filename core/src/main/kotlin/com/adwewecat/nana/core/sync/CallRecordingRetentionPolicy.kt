package com.adwewecat.nana.core.sync

import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.SyncStatus

/**
 * Selects local staged call recordings that are strictly verified on Google Drive and older
 * than [retentionDaysAfterVerifiedSync] days, unless [keepForever] is enabled.
 */
class CallRecordingRetentionPolicy(
    val retentionDaysAfterVerifiedSync: Int = 7,
    val keepForever: Boolean = false,
) {
    init {
        require(retentionDaysAfterVerifiedSync >= 0) {
            "retentionDaysAfterVerifiedSync must be >= 0"
        }
    }

    fun selectStagedFilesEligibleForCleanup(
        records: List<CallRecordingRecord>,
        nowMs: Long,
    ): List<CallRecordingRecord> {
        if (keepForever) return emptyList()
        val retentionWindowMs = retentionDaysAfterVerifiedSync.toLong() * MILLIS_PER_DAY
        return records.filter { record ->
            val syncedAt = record.syncedAtMs ?: return@filter false
            record.syncStatus == SyncStatus.SYNCED &&
                !record.driveFileId.isNullOrBlank() &&
                !record.stagedPath.isNullOrBlank() &&
                nowMs - syncedAt >= retentionWindowMs
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
