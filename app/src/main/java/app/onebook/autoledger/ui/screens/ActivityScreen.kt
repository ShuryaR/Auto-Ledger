package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.inbox.ActivityPeriod
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.ingest.AutoApply
import app.onebook.autoledger.ingest.AutoDoneMark
import app.onebook.autoledger.ingest.StatusPoller
import app.onebook.autoledger.outbox.OutboxRunner
import app.onebook.autoledger.outbox.UndoResult
import app.onebook.autoledger.state.ActivityFilter
import app.onebook.autoledger.state.ActivityModel
import app.onebook.autoledger.state.ActivitySort
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.state.ConfirmDialogText
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.CombinedFilters
import app.onebook.autoledger.ui.components.ExternalOpenButton
import app.onebook.autoledger.ui.components.FilterMode
import app.onebook.autoledger.ui.components.FilterSheetDialog
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerChip
import app.onebook.autoledger.ui.components.LedgerConfirmDialog
import app.onebook.autoledger.ui.components.LedgerEmptyState
import app.onebook.autoledger.ui.components.LedgerLazyScreen
import app.onebook.autoledger.ui.components.LedgerSearchField
import app.onebook.autoledger.ui.components.LedgerStatusChip
import app.onebook.autoledger.ui.components.openExternalUrl
import app.onebook.autoledger.ui.theme.LedgerTheme
import app.onebook.autoledger.ui.theme.tnum
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityScreen(
    container: AppContainer,
    onSignInAgain: () -> Unit,
) {
    val c = LedgerTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bootState by container.state.collectAsState()

    var allTxns by remember { mutableStateOf<List<PendingTxn>>(emptyList()) }
    var autoMarks by remember { mutableStateOf<Map<String, AutoDoneMark>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(ActivityPeriod.THIS_MONTH) }
    var filters by remember { mutableStateOf(CombinedFilters()) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var rowLimit by remember { mutableIntStateOf(ActivityModel.PAGE_SIZE) }
    var refreshing by remember { mutableStateOf(false) }

    var actionFeedback by remember { mutableStateOf<Pair<BannerTone, String>?>(null) }
    var undoCandidate by remember { mutableStateOf<Pair<PendingTxn, ConfirmDialogText>?>(null) }
    var undoBusy by remember { mutableStateOf(false) }

    suspend fun loadLocal() {
        val list = container.repo.listPending(
            listOf(TxnState.CREATED, TxnState.SUBMITTED, TxnState.IGNORED),
        )
        val marks = container.repo.listSettings(AutoApply.AUTO_DONE_PREFIX)
            .mapNotNull { (k, v) ->
                val mark = AutoDoneMark.parse(v) ?: return@mapNotNull null
                k.removePrefix(AutoApply.AUTO_DONE_PREFIX) to mark
            }
            .toMap()
        allTxns = list
        autoMarks = marks
        loaded = true
    }

    LaunchedEffect(bootState.dataVersion) {
        loadLocal()
    }

    LaunchedEffect(Unit) {
        val erp = container.erp ?: return@LaunchedEffect
        try {
            val r = StatusPoller.pollStatuses(container.repo, erp, System::currentTimeMillis)
            if (r.needsLogin) container.refreshBanners()
            if (r.checked > 0) loadLocal()
        } catch (_: Throwable) {
            // Best effort
        }
    }

    val today = remember(allTxns.size) { ActivityModel.isoToday(System.currentTimeMillis()) }
    val accounts = remember(allTxns) {
        allTxns.mapNotNull { it.accountLast4?.takeIf { l -> l.isNotBlank() } }.distinct().sorted()
    }
    val filterObj = remember(query, period, filters) {
        ActivityFilter(
            query = query,
            period = period,
            direction = filters.direction,
            accountLast4 = filters.accountLast4,
            status = filters.status,
        )
    }
    val filtered = remember(allTxns, filterObj, filters.sort, today) {
        ActivityModel.sortActivity(
            ActivityModel.filterActivity(allTxns, filterObj, today),
            filters.sort,
        )
    }
    val totals = remember(filtered) { ActivityModel.totalsOf(filtered) }
    val waiting = remember(allTxns, filterObj, today) { ActivityModel.waitingCount(allTxns, filterObj, today) }
    val groups = remember(filtered, rowLimit, filters.sort, today) {
        val paged = filtered.take(rowLimit)
        if (filters.sort == ActivitySort.NEWEST || filters.sort == ActivitySort.OLDEST) {
            ActivityModel.groupByRecency(paged, today)
        } else {
            emptyList()
        }
    }
    val activeFilterCount = filters.activeCount(FilterMode.ACTIVITY)
    val siteUrl = bootState.session?.siteUrl.orEmpty()

    LedgerLazyScreen(
        title = "Activity",
        subtitle = "$waiting waiting for your accountant",
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    val erp = container.erp
                    if (erp != null) {
                        StatusPoller.pollStatuses(container.repo, erp, System::currentTimeMillis)
                    }
                    container.refreshBanners()
                    loadLocal()
                } finally {
                    refreshing = false
                }
            }
        },
        headerExtra = {
            Column(modifier = Modifier.fillMaxWidth()) {
                FlowRow {
                    LedgerChip("This month", period == ActivityPeriod.THIS_MONTH, { period = ActivityPeriod.THIS_MONTH })
                    LedgerChip("Last month", period == ActivityPeriod.LAST_MONTH, { period = ActivityPeriod.LAST_MONTH })
                    LedgerChip("All time", period == ActivityPeriod.ALL, { period = ActivityPeriod.ALL })
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LedgerSearchField(
                        value = query,
                        onValueChange = {
                            query = it
                            rowLimit = ActivityModel.PAGE_SIZE
                        },
                        placeholder = "Search party, amount, entry",
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
            }
        },
    ) {
        val fb = actionFeedback
        if (fb != null) {
            item(key = "__feedback__") {
                LedgerBanner(
                    tone = fb.first,
                    message = fb.second,
                    actionLabel = if (fb.second.contains("Sign in", ignoreCase = true)) "Sign in again" else "Dismiss",
                    onAction = {
                        if (fb.second.contains("Sign in", ignoreCase = true)) onSignInAgain()
                        actionFeedback = null
                    },
                )
            }
        }

        if (!loaded) {
            item(key = "__loading__") {
                LedgerBanner(message = "Loading activity...")
            }
            return@LedgerLazyScreen
        }

        if (filtered.isNotEmpty()) {
            item(key = "__totals__") {
                LedgerCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Money in", fontSize = 12.5.sp, color = c.muted)
                            Text(
                                text = Formatters.formatInr(totals.inMinor),
                                style = MaterialTheme.typography.titleMedium.tnum,
                                fontWeight = FontWeight.Bold,
                                color = c.moneyIn,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Money out", fontSize = 12.5.sp, color = c.muted)
                            Text(
                                text = Formatters.formatInr(totals.outMinor),
                                style = MaterialTheme.typography.titleMedium.tnum,
                                fontWeight = FontWeight.Bold,
                                color = c.moneyOut,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Net", fontSize = 12.5.sp, color = c.muted)
                            Text(
                                text = if (totals.netMinor > 0L) "+${Formatters.formatInr(totals.netMinor)}" else Formatters.formatInr(totals.netMinor),
                                style = MaterialTheme.typography.titleMedium.tnum,
                                fontWeight = FontWeight.Bold,
                                color = c.ink,
                            )
                        }
                    }
                    if (totals.foreignCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Excludes ${totals.foreignCount} in other currencies.",
                            fontSize = 12.5.sp,
                            color = c.muted,
                        )
                    }
                }
            }
        }

        if (allTxns.isEmpty()) {
            item(key = "__empty__") {
                LedgerEmptyState(
                    title = "No answered entries yet",
                    message = "Transactions you confirm or ignore from the Inbox will show up here.",
                )
            }
        } else if (filtered.isEmpty()) {
            item(key = "__no_match__") {
                LedgerEmptyState(
                    title = "Nothing matches these filters",
                    message = "Try changing the period, search text, or filters.",
                    actionLabel = "Reset filters",
                    onAction = {
                        query = ""
                        period = ActivityPeriod.ALL
                        filters = CombinedFilters()
                    },
                )
            }
        } else if (groups.isNotEmpty()) {
            groups.forEach { group ->
                item(key = "hdr:${group.title.name}") {
                    Text(
                        text = group.title.label,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.muted,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                }
                items(group.rows, key = { it.id }) { txn ->
                    ActivityRowCard(
                        txn = txn,
                        today = today,
                        siteUrl = siteUrl,
                        autoStatus = ActivityModel.autoChip(txn, autoMarks),
                        onOpenDoc = { dt, name ->
                            if (siteUrl.isNotBlank()) {
                                openExternalUrl(context, ActivityModel.docUrl(siteUrl, dt, name))
                            }
                        },
                        onUndoOrPutBack = {
                            val plan = ActivityModel.removalPlan(txn, allTxns)
                            val text = ActivityModel.confirmText(plan)
                            if (text != null) {
                                undoCandidate = txn to text
                            } else {
                                // Cancelled/Missing put back straight into Inbox
                                scope.launch {
                                    container.repo.upsertPending(
                                        txn.copy(state = TxnState.NEW, createdDocs = emptyList(), error = null),
                                    )
                                    container.bumpData()
                                    actionFeedback = BannerTone.OK to "Put back in your Inbox."
                                }
                            }
                        },
                        onBringBack = {
                            scope.launch {
                                val ok = StatusPoller.bringBack(txn.id, container.repo)
                                if (ok) {
                                    container.bumpData()
                                    actionFeedback = BannerTone.OK to "Moved back to your Inbox."
                                }
                            }
                        },
                    )
                }
            }
        } else {
            items(filtered.take(rowLimit), key = { it.id }) { txn ->
                ActivityRowCard(
                    txn = txn,
                    today = today,
                    siteUrl = siteUrl,
                    autoStatus = ActivityModel.autoChip(txn, autoMarks),
                    onOpenDoc = { dt, name ->
                        if (siteUrl.isNotBlank()) {
                            openExternalUrl(context, ActivityModel.docUrl(siteUrl, dt, name))
                        }
                    },
                    onUndoOrPutBack = {
                        val plan = ActivityModel.removalPlan(txn, allTxns)
                        val text = ActivityModel.confirmText(plan)
                        if (text != null) {
                            undoCandidate = txn to text
                        } else {
                            scope.launch {
                                container.repo.upsertPending(
                                    txn.copy(state = TxnState.NEW, createdDocs = emptyList(), error = null),
                                )
                                container.bumpData()
                                actionFeedback = BannerTone.OK to "Put back in your Inbox."
                            }
                        }
                    },
                    onBringBack = {
                        scope.launch {
                            val ok = StatusPoller.bringBack(txn.id, container.repo)
                            if (ok) {
                                container.bumpData()
                                actionFeedback = BannerTone.OK to "Moved back to your Inbox."
                            }
                        }
                    },
                )
            }
        }

        if (filtered.size > rowLimit) {
            item(key = "__load_more__") {
                LedgerButton(
                    label = "Show more (${filtered.size - rowLimit} left)",
                    variant = ButtonVariant.SECONDARY,
                    onClick = { rowLimit += ActivityModel.PAGE_SIZE },
                )
            }
        }
    }

    FilterSheetDialog(
        visible = showFilterSheet,
        value = filters,
        accounts = accounts,
        mode = FilterMode.ACTIVITY,
        onApply = { applied ->
            filters = applied
            rowLimit = ActivityModel.PAGE_SIZE
            showFilterSheet = false
        },
        onReset = {
            filters = CombinedFilters()
            rowLimit = ActivityModel.PAGE_SIZE
            showFilterSheet = false
        },
        onDismiss = { showFilterSheet = false },
    )

    val pendingUndo = undoCandidate
    if (pendingUndo != null) {
        val (txn, dlg) = pendingUndo
        LedgerConfirmDialog(
            visible = true,
            title = dlg.title,
            message = dlg.message,
            confirmLabel = if (undoBusy) "Working..." else "Confirm",
            destructive = true,
            onConfirm = {
                if (undoBusy) return@LedgerConfirmDialog
                val erp = container.erp ?: return@LedgerConfirmDialog
                scope.launch {
                    undoBusy = true
                    try {
                        when (val res = OutboxRunner.undoTxn(txn.id, container.repo, erp)) {
                            is UndoResult.Undone -> {
                                container.bumpData()
                                container.bumpHome()
                                actionFeedback = BannerTone.OK to "Removed and put back in your Inbox."
                            }
                            is UndoResult.Refused -> {
                                container.bumpData()
                                actionFeedback = BannerTone.WARN to res.message
                            }
                            is UndoResult.Failed -> {
                                actionFeedback = BannerTone.ERROR to (res.error.message ?: "Could not remove the draft.")
                            }
                            UndoResult.NeedsLogin -> {
                                actionFeedback = BannerTone.WARN to "ERPNext did not accept your saved login. Sign in again."
                            }
                        }
                    } finally {
                        undoBusy = false
                        undoCandidate = null
                    }
                }
            },
            onDismiss = { if (!undoBusy) undoCandidate = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityRowCard(
    txn: PendingTxn,
    today: String,
    siteUrl: String,
    autoStatus: app.onebook.autoledger.state.RowStatus?,
    onOpenDoc: (String, String) -> Unit,
    onUndoOrPutBack: () -> Unit,
    onBringBack: () -> Unit,
) {
    val c = LedgerTheme.colors
    val rowStatus = ActivityModel.statusOf(txn)
    val amtColor = if (txn.direction == Direction.IN) c.moneyIn else c.moneyOut

    LedgerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ActivityModel.titleOf(txn),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOf(
                        Formatters.kindLabel(txn.kind),
                        Formatters.whenLabel(txn, today),
                    ).joinToString(" \u00B7 "),
                    fontSize = 12.5.sp,
                    color = c.muted,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = Formatters.signedAmount(txn),
                style = MaterialTheme.typography.titleMedium.tnum,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = amtColor,
            )
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerStatusChip(status = rowStatus)
            if (autoStatus != null) LedgerStatusChip(status = autoStatus)
        }

        if (!rowStatus.hint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(rowStatus.hint, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        }

        val showUndo = ActivityModel.canUndo(txn)
        val showPutBack = ActivityModel.canPutBack(txn)
        val showBringBack = txn.state == TxnState.IGNORED
        val docs = txn.createdDocs.filter { it.docstatus != StatusPoller.DOC_MISSING }

        if (docs.isNotEmpty() || showUndo || showPutBack || showBringBack) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (siteUrl.isNotBlank()) {
                    docs.forEach { d ->
                        ExternalOpenButton(
                            label = if (docs.size == 1) "Open in ERPNext" else "Open ${d.name}",
                            onClick = { onOpenDoc(d.doctype, d.name) },
                        )
                    }
                }
                if (showUndo) {
                    LedgerButton(
                        label = "Undo",
                        onClick = onUndoOrPutBack,
                        variant = ButtonVariant.SECONDARY,
                    )
                } else if (showPutBack) {
                    LedgerButton(
                        label = "Put back in Inbox",
                        onClick = onUndoOrPutBack,
                        variant = ButtonVariant.SECONDARY,
                    )
                } else if (showBringBack) {
                    LedgerButton(
                        label = "Bring back to Inbox",
                        onClick = onBringBack,
                        variant = ButtonVariant.SECONDARY,
                    )
                }
            }
        }
    }
}
