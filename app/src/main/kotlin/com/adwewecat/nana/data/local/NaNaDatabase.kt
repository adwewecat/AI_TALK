package com.adwewecat.nana.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Room database backing local offline-first chat history, memory facts, recordings, and sync metadata. */
@Database(
    entities = [
        MessageEntity::class,
        MemoryFactEntity::class,
        CallRecordingEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class NaNaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun callRecordingDao(): CallRecordingDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        private const val DATABASE_NAME = "nana.db"

        @Volatile
        private var instance: NaNaDatabase? = null

        /** Returns the process-wide singleton [NaNaDatabase] backed by `nana.db`. */
        fun getInstance(context: Context): NaNaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NaNaDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }

        /** Creates an isolated in-memory [NaNaDatabase] suitable for unit tests. */
        fun createInMemory(context: Context): NaNaDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                NaNaDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
    }
}
