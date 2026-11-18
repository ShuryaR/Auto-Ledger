package app.onebook.autoledger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_txn",
    indices = [
        Index(value = ["raw_hash"], unique = true),
        Index(value = ["state"]),
    ],
)
data class PendingTxnEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "raw_hash") val rawHash: String,
    val state: String,
    @ColumnInfo(name = "merged_count") val mergedCount: Int,
    @ColumnInfo(name = "created_docs") val createdDocs: String,
    val error: String?,
    val kind: String,
    val direction: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "occurred_on") val occurredOn: String,
    @ColumnInfo(name = "occurred_time") val occurredTime: String?,
    @ColumnInfo(name = "account_type") val accountType: String,
    @ColumnInfo(name = "account_last4") val accountLast4: String?,
    val counterparty: String?,
    val utr: String?,
    val merchant: String?,
    @ColumnInfo(name = "balance_minor") val balanceMinor: Long?,
    val sender: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

@Entity(tableName = "account_mapping")
data class AccountMappingEntity(
    @PrimaryKey @ColumnInfo(name = "bank_account") val bankAccount: String,
    @ColumnInfo(name = "gl_account") val glAccount: String,
    val kind: String,
    @ColumnInfo(name = "sender_ids") val senderIds: String,
    val last4: String,
    @ColumnInfo(name = "mode_of_payment") val modeOfPayment: String,
)

@Entity(tableName = "rule")
data class RuleEntity(
    @PrimaryKey @ColumnInfo(name = "match_key") val matchKey: String,
    val action: String,
    val party: String?,
    val account: String?,
    val hits: Int,
    @ColumnInfo(name = "last_used") val lastUsed: Long,
    val auto: Int,
    @ColumnInfo(name = "max_amount_minor") val maxAmountMinor: Long?,
    val currency: String?,
)

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["txn_id"]),
        Index(value = ["status"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0L,
    val id: String,
    @ColumnInfo(name = "txn_id") val txnId: String,
    val plan: String,
    val progress: String,
    val attempts: Int,
    val status: String,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
