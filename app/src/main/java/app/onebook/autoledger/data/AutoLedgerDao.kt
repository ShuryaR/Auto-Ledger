package app.onebook.autoledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class NewSourceRow(
    val sender: String,
    @androidx.room.ColumnInfo(name = "account_type") val accountType: String,
    @androidx.room.ColumnInfo(name = "account_last4") val accountLast4: String?,
)

@Dao
interface AutoLedgerDao {
    @Query("SELECT * FROM pending_txn ORDER BY received_at, id")
    suspend fun listAllPending(): List<PendingTxnEntity>

    @Query("SELECT * FROM pending_txn WHERE state IN (:states) ORDER BY received_at, id")
    suspend fun listPendingByStates(states: List<String>): List<PendingTxnEntity>

    @Query("SELECT COUNT(*) FROM pending_txn WHERE state = :state")
    suspend fun countPending(state: String): Int

    @Query("SELECT DISTINCT sender, account_type, account_last4 FROM pending_txn WHERE state = 'new'")
    suspend fun listNewSources(): List<NewSourceRow>

    @Query("SELECT * FROM pending_txn WHERE id = :id LIMIT 1")
    suspend fun getPendingById(id: String): PendingTxnEntity?

    @Query("SELECT * FROM pending_txn WHERE raw_hash = :rawHash LIMIT 1")
    suspend fun getPendingByRawHash(rawHash: String): PendingTxnEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePending(entity: PendingTxnEntity)

    @Transaction
    suspend fun upsertPendingSafe(entity: PendingTxnEntity) {
        val byHash = getPendingByRawHash(entity.rawHash)
        if (byHash != null && byHash.id != entity.id) {
            return
        }
        replacePending(entity)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMapping(entity: AccountMappingEntity)

    @Query("SELECT * FROM account_mapping ORDER BY bank_account")
    suspend fun listMappings(): List<AccountMappingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRule(entity: RuleEntity)

    @Query("DELETE FROM rule WHERE match_key = :matchKey")
    suspend fun deleteRule(matchKey: String)

    @Query("SELECT * FROM rule ORDER BY match_key")
    suspend fun listRules(): List<RuleEntity>

    @Insert
    suspend fun insertOutbox(entity: OutboxEntity)

    @Query("UPDATE outbox SET status = 'superseded' WHERE txn_id = :txnId AND id != :exceptId AND status = 'pending'")
    suspend fun supersedeOtherPendingOutbox(txnId: String, exceptId: String)

    @Query("SELECT * FROM outbox WHERE id = :id LIMIT 1")
    suspend fun getOutbox(id: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE txn_id = :txnId ORDER BY seq")
    suspend fun listOutboxForTxn(txnId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE status IN ('pending', 'failed') ORDER BY seq")
    suspend fun listOpenOutbox(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE status = 'pending' ORDER BY seq LIMIT 1")
    suspend fun nextPendingOutbox(): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE status IN ('pending', 'done') ORDER BY seq")
    suspend fun listActiveOutbox(): List<OutboxEntity>

    @Query("SELECT DISTINCT txn_id FROM outbox WHERE status = 'pending' ORDER BY txn_id")
    suspend fun listQueuedTxnIds(): List<String>

    @Query("UPDATE outbox SET progress = :progress, status = CASE WHEN status = 'superseded' THEN status ELSE 'done' END WHERE id = :id")
    suspend fun finishOutbox(id: String, progress: String)

    @Query("UPDATE outbox SET progress = :progress WHERE id = :id")
    suspend fun updateOutboxProgress(id: String, progress: String)

    @Query("UPDATE outbox SET attempts = :attempts WHERE id = :id")
    suspend fun updateOutboxAttempts(id: String, attempts: Int)

    @Query("UPDATE outbox SET status = :status WHERE id = :id")
    suspend fun updateOutboxStatus(id: String, status: String)

    @Query("SELECT value FROM settings WHERE key = :key LIMIT 1")
    suspend fun getSetting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(entity: SettingEntity)

    @Query("SELECT * FROM settings WHERE substr(key, 1, :prefixLen) = :prefix ORDER BY key")
    suspend fun listSettings(prefixLen: Int, prefix: String): List<SettingEntity>

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun deleteSetting(key: String)

    @Query("DELETE FROM pending_txn")
    suspend fun deleteAllPending()

    @Query("DELETE FROM account_mapping")
    suspend fun deleteAllMappings()

    @Query("DELETE FROM rule")
    suspend fun deleteAllRules()

    @Query("DELETE FROM outbox")
    suspend fun deleteAllOutbox()

    @Query("DELETE FROM settings WHERE key NOT LIKE 'templates\\_%' ESCAPE '\\'")
    suspend fun deleteNonTemplateSettings()

    @Transaction
    suspend fun resetForNewSite() {
        deleteAllPending()
        deleteAllMappings()
        deleteAllRules()
        deleteAllOutbox()
        deleteNonTemplateSettings()
    }
}
