package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.home.HomeLogic
import app.onebook.autoledger.inbox.ChipCache
import app.onebook.autoledger.inbox.InboxFilter
import app.onebook.autoledger.inbox.InboxInputs
import app.onebook.autoledger.inbox.InboxItem
import app.onebook.autoledger.inbox.InboxModel
import app.onebook.autoledger.inbox.InboxModelBuilder
import app.onebook.autoledger.ingest.AutoApply
import app.onebook.autoledger.ingest.IngestPipeline
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.CombinedFilters
import app.onebook.autoledger.ui.components.FilterMode
import app.onebook.autoledger.ui.components.FilterSheetDialog
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerEmptyState
import app.onebook.autoledger.ui.components.LedgerLazyScreen
import app.onebook.autoledger.ui.components.LedgerSearchField
import app.onebook.autoledger.ui.components.LedgerTxnRow
import app.onebook.autoledger.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

private const val PAGE_STEP = 50

@Composable
fun InboxScreen(
    container: AppContainer,
    onOpenTxn: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenSettingsAccounts: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val c = LedgerTheme.colors
    val scope = rememberCoroutineScope()
    val bootState by container.state.collectAsState()
    val flowState by container.flowStore.state.collectAsState()

    val chipCache = remember { ChipCache() }
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(CombinedFilters()) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var rowLimit by remember { mutableIntStateOf(PAGE_STEP) }
    var refreshing by remember { mutableStateOf(false) }
    var rawInputs by remember { mutableStateOf<InboxInputs?>(null) }

    LaunchedEffect(bootState.dataVersion) {
        val pending = container.repo.listPending(listOf(TxnState.NEW))
        val mappings = container.repo.listMappings()
        val rules = container.repo.listRules()
        val createdCount = container.repo.countPending(TxnState.CREATED)
        val unreadCount = container.repo.getSetting(IngestPipeline.UNRECOGNISED_COUNT_KEY)?.toIntOrNull() ?: 0
        val unknownRows = container.repo.listSettings("unrecognised_account:")
        val unknownLast4 = unknownRows.map { it.first.removePrefix("unrecognised_account:") }.filter { it.isNotBlank() }
        val lastSynced = container.repo.getSetting(HomeLogic.LAST_SYNCED_KEY)?.toLongOrNull()
        val queuedIds = container.repo.listQueuedTxnIds()

        val autoFailed = container.repo.listSettings(AutoApply.AUTO_FAILED_PREFIX)
            .associate { (k, v) -> k.removePrefix(AutoApply.AUTO_FAILED_PREFIX) to v }
        val autoHeld = container.repo.listSettings(AutoApply.AUTO_HELD_PREFIX)
            .associate { (k, v) -> k.removePrefix(AutoApply.AUTO_HELD_PREFIX) to v }
        val autoDoneCount = container.repo.getSetting(AutoApply.AUTO_NEW_COUNT_KEY)?.toIntOrNull() ?: 0

        chipCache.prune(pending.mapTo(HashSet()) { it.id })
        rawInputs = InboxInputs(
            pending = pending,
            mappings = mappings,
            rules = rules,
            createdCount = createdCount,
            unreadCount = unreadCount,
            unknownAccounts = unknownLast4,
            lastSyncedAt = lastSynced,
            now = System.currentTimeMillis(),
            queuedIds = queuedIds,
            chips = chipCache,
            autoFailed = autoFailed,
            autoHeld = autoHeld,
            autoDoneCount = autoDoneCount,
        )
    }

    val model: InboxModel? = remember(rawInputs, query, filters) {
        rawInputs?.let { base ->
            InboxModelBuilder.build(
                base.copy(
                    now = System.currentTimeMillis(),
                    filter = InboxFilter(
                        query = query,
                        direction = filters.direction,
                        accountLast4 = filters.accountLast4,
                        kind = filters.kind,
                    ),
                )
            )
        }
    }

    val activeFilterCount = filters.activeCount(FilterMode.INBOX)
    val pagedItems = remember(model, rowLimit) {
        model?.let { InboxModelBuilder.pageItems(it.sections, rowLimit) }.orEmpty()
    }
    val hasMoreRows = model != null && model.rows.size > rowLimit

    LedgerLazyScreen(
        title = "Inbox",
        subtitle = model?.let { "${it.countLabel} \u00B7 ${it.trustLine}" } ?: "Loading...",
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    container.runCycle()
                } finally {
                    refreshing = false
                }
            }
        },
        headerExtra = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LedgerSearchField(
                    value = query,
                    onValueChange = {
                        query = it
                        rowLimit = PAGE_STEP
                    },
                    placeholder = "Search party, amount, UTR",
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { showFilterSheet = true },
                    modifier = Modifier.heightIn(min = 56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (activeFilterCount > 0) c.accentSoft else c.surface,
                        contentColor = if (activeFilterCount > 0) c.accent else c.ink,
                    ),
                ) {
                    BadgedBox(
                        badge = {
                            if (activeFilterCount > 0) {
                                Badge(containerColor = c.accent, contentColor = c.surface) {
                                    Text(activeFilterCount.toString())
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.FilterList, contentDescription = "Filters")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Filter", fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        val flash = flowState.flash
        if (!flash.isNullOrBlank()) {
            item(key = "__flash__") {
                LedgerBanner(
                    tone = BannerTone.OK,
                    message = flash,
                    actionLabel = "Dismiss",
                    onAction = { container.flowStore.setFlash(null) },
                )
            }
        }

        if (model == null) {
            item(key = "__loading__") {
                LedgerBanner(message = "Reading your Inbox...")
            }
            return@LedgerLazyScreen
        }

        if (model.autoDoneText != null) {
            item(key = "__auto_done__") {
                LedgerBanner(
                    tone = BannerTone.OK,
                    message = model.autoDoneText,
                    actionLabel = "See Activity",
                    onAction = {
                        scope.launch {
                            container.repo.setSetting(AutoApply.AUTO_NEW_COUNT_KEY, "0")
                            container.bumpData()
                        }
                        onOpenActivity()
                    },
                    secondaryLabel = "Dismiss",
                    onSecondary = {
                        scope.launch {
                            container.repo.setSetting(AutoApply.AUTO_NEW_COUNT_KEY, "0")
                            container.bumpData()
                        }
                    },
                )
            }
        }

        if (model.accountsNotice != null) {
            item(key = "__unknown_accounts__") {
                LedgerBanner(
                    tone = BannerTone.WARN,
                    title = "Unrecognised account",
                    message = model.accountsNotice.text,
                    actionLabel = "Set up in Settings",
                    onAction = onOpenSettingsAccounts,
                )
            }
        }

        if (model.unreadText != null) {
            item(key = "__unread_sms__") {
                LedgerBanner(
                    tone = BannerTone.INFO,
                    compact = true,
                    message = model.unreadText,
                    actionLabel = "Dismiss",
                    onAction = {
                        scope.launch {
                            container.repo.setSetting(IngestPipeline.UNRECOGNISED_COUNT_KEY, "0")
                            container.bumpData()
                        }
                    },
                )
            }
        }

        if (model.autoFailed.isNotEmpty()) {
            item(key = "__auto_failed__") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    model.autoFailed.take(3).forEach { fail ->
                        LedgerBanner(
                            tone = BannerTone.WARN,
                            compact = true,
                            message = fail.text,
                            actionLabel = "Open",
                            onAction = { onOpenTxn(fail.id) },
                        )
                    }
                }
            }
        }

        if (model.groups.isNotEmpty()) {
            item(key = "__groups_header__") {
                Text(
                    text = "Answer together",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
            items(model.groups, key = { "grp:${it.key}" }) { grp ->
                LedgerCard(accentBorder = true, onClick = { onOpenGroup(grp.key) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = grp.title,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.ink,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = grp.answer,
                                fontSize = 13.5.sp,
                                color = c.accent,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.accent)
                    }
                }
            }
        }

        if (model.empty) {
            item(key = "__empty__") {
                LedgerEmptyState(
                    title = "You are all caught up",
                    message = "New bank and card SMS messages will appear here ready for your answer.",
                    actionLabel = "Scan for SMS now",
                    onAction = {
                        scope.launch {
                            refreshing = true
                            try {
                                container.runCycle()
                            } finally {
                                refreshing = false
                            }
                        }
                    },
                )
            }
        } else if (model.noMatch) {
            item(key = "__no_match__") {
                LedgerEmptyState(
                    title = "No matching messages",
                    message = "Try clearing your search or filters.",
                    actionLabel = "Clear filters",
                    onAction = {
                        query = ""
                        filters = CombinedFilters()
                    },
                )
            }
        } else {
            items(pagedItems, key = { it.key }) { item ->
                when (item) {
                    is InboxItem.Header -> {
                        Text(
                            text = item.title,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.muted,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                        )
                    }
                    is InboxItem.Row -> {
                        LedgerTxnRow(
                            row = item.row,
                            chip = model.chipOf(item.row.id),
                            onClick = { onOpenTxn(item.row.id) },
                        )
                    }
                }
            }

            if (hasMoreRows) {
                item(key = "__load_more__") {
                    LedgerButton(
                        label = "Show more (${model.rows.size - rowLimit} left)",
                        variant = ButtonVariant.SECONDARY,
                        onClick = { rowLimit += PAGE_STEP },
                    )
                }
            }
        }
    }

    FilterSheetDialog(
        visible = showFilterSheet,
        value = filters,
        accounts = model?.accounts.orEmpty(),
        mode = FilterMode.INBOX,
        onApply = { applied ->
            filters = applied
            rowLimit = PAGE_STEP
            showFilterSheet = false
        },
        onReset = {
            filters = CombinedFilters()
            rowLimit = PAGE_STEP
            showFilterSheet = false
        },
        onDismiss = { showFilterSheet = false },
    )
}
