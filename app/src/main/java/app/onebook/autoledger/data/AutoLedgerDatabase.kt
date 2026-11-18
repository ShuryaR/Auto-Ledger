package app.onebook.autoledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PendingTxnEntity::class,
        AccountMappingEntity::class,
        RuleEntity::class,
        OutboxEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AutoLedgerDatabase : RoomDatabase() {
    abstract fun dao(): AutoLedgerDao

    companion object {
        @Volatile
        private var instance: AutoLedgerDatabase? = null

        fun getInstance(context: Context): AutoLedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoLedgerDatabase::class.java,
                    "autoledger.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { instance = it }
            }
    }
}
