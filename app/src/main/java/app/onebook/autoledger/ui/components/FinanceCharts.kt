package app.onebook.autoledger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.erp.AgeingBucket
import app.onebook.autoledger.erp.AgeingBuckets
import app.onebook.autoledger.erp.MonthPerformance
import app.onebook.autoledger.home.HomeBankAccount
import app.onebook.autoledger.home.HomeLogic
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.reports.RunwayAnalysis
import app.onebook.autoledger.ui.theme.LedgerTheme
import app.onebook.autoledger.ui.theme.tnum
import kotlin.math.abs
import kotlin.math.max

@Composable
fun HeroCashRunwayCard(
    liquidCashMinor: Long?,
    accounts: List<HomeBankAccount>,
    runwayHeadline: String?,
    runwayIsPositive: Boolean?,
    asOfLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val heroGlow = c.heroGlow

    val cashText = when {
        liquidCashMinor != null -> Formatters.formatInr(liquidCashMinor)
        accounts.size == 1 -> accounts[0].text
        else -> HomeLogic.DASH
    }
    val accountSubtitle = when {
        accounts.isEmpty() -> "No mapped bank accounts yet"
        accounts.size == 1 -> accounts[0].name
        else -> "Across ${accounts.size} bank accounts"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag("hero_cash_runway_card"),
        shape = shape,
        color = c.heroBg,
        border = BorderStroke(1.dp, c.heroAccent.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                heroGlow.copy(alpha = 0.36f),
                                heroGlow.copy(alpha = 0.0f),
                            ),
                            center = Offset(size.width * 0.9f, size.height * 0.1f),
                            radius = size.width * 0.72f,
                        ),
                        radius = size.width * 0.72f,
                        center = Offset(size.width * 0.9f, size.height * 0.1f),
                    )
                }
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TOTAL LIQUID CASH",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 0.9.sp,
                    color = c.heroMuted,
                    fontWeight = FontWeight.SemiBold,
                )
                val badgeBg = when (runwayIsPositive) {
                    true -> Color(0xFF14533C)
                    false -> Color(0xFF283A73)
                    null -> Color(0xFF253463)
                }
                val badgeFg = when (runwayIsPositive) {
                    true -> Color(0xFF6EE7B7)
                    else -> c.heroInk
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeFg.copy(alpha = 0.25f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                            contentDescription = null,
                            tint = badgeFg,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = runwayHeadline ?: "View MIS",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeFg,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = cashText,
                style = MaterialTheme.typography.displayLarge.tnum,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                color = c.heroInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(accountSubtitle, asOfLabel).joinToString(" \u00B7 "),
                style = MaterialTheme.typography.bodySmall,
                color = c.heroMuted,
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = c.heroInk.copy(alpha = 0.12f))
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MIS, Cash Runway & Burn Analysis",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    color = c.heroAccent,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Open MIS & Runway",
                    tint = c.heroAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun <T> SegmentedPillBar(
    items: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "segment",
) {
    val c = LedgerTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = c.surfaceSubtle,
        border = BorderStroke(1.dp, c.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { item ->
                val isSelected = item == selected
                val lbl = labelOf(item)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(item) }
                        .testTag("${tagPrefix}_${lbl.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) c.accent else Color.Transparent,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = lbl,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) (if (c.isDark) Color(0xFF090F1C) else Color.White) else c.muted,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgeingStackedBar(
    buckets: AgeingBuckets,
    selectedBucket: AgeingBucket?,
    onSelectBucket: (AgeingBucket?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val bucketList = AgeingBucket.entries
    val values = buckets.list().map { max(0L, it) }
    val total = values.sum()
    val colors = c.ageingBuckets

    Column(modifier = modifier.fillMaxWidth()) {
        if (total > 0L) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(c.surfaceSubtle),
            ) {
                val nonZero = values.count { it > 0L }
                val gapPx = if (nonZero > 1) 2.dp.toPx() else 0f
                val totalGap = gapPx * max(0, nonZero - 1)
                val usableWidth = max(1f, size.width - totalGap)
                var x = 0f
                values.forEachIndexed { idx, v ->
                    if (v > 0L) {
                        val w = (v.toFloat() / total.toFloat()) * usableWidth
                        val color = if (selectedBucket == null || selectedBucket == bucketList[idx]) {
                            colors[idx]
                        } else {
                            colors[idx].copy(alpha = 0.25f)
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(x, 0f),
                            size = Size(max(4.dp.toPx(), w), size.height),
                        )
                        x += max(4.dp.toPx(), w) + gapPx
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgeingFilterChip(
                label = "All buckets",
                amountText = Formatters.formatInr(buckets.total),
                dotColor = c.accent,
                selected = selectedBucket == null,
                onClick = { onSelectBucket(null) },
                modifier = Modifier.testTag("ageing_bucket_all"),
            )
            bucketList.forEachIndexed { idx, bucket ->
                val amt = buckets.amountFor(bucket)
                AgeingFilterChip(
                    label = bucket.shortLabel,
                    amountText = HomeLogic.wholeText(amt),
                    dotColor = colors[idx],
                    selected = selectedBucket == bucket,
                    onClick = { onSelectBucket(if (selectedBucket == bucket) null else bucket) },
                    modifier = Modifier.testTag("ageing_bucket_${bucket.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun AgeingFilterChip(
    label: String,
    amountText: String,
    dotColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) c.accentSoft else c.surfaceSubtle,
        border = BorderStroke(1.dp, if (selected) c.accent else c.line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = label,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) c.accent else c.muted,
                )
                Text(
                    text = amountText,
                    style = MaterialTheme.typography.labelLarge.tnum,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.ink,
                )
            }
        }
    }
}

@Composable
fun MonthlyInOutBarChart(
    months: List<MonthPerformance>,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val displayed = remember(months) { months.takeLast(6) }
    var selectedIdx by remember(displayed) { mutableIntStateOf((displayed.lastIndex).coerceAtLeast(0)) }

    if (displayed.isEmpty()) {
        Text(
            text = "No monthly performance data yet.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
        )
        return
    }

    val maxMinor = max(
        1L,
        displayed.maxOf { max(abs(it.incomeMinor), abs(it.expenseMinor)) },
    )
    val selectedMonth = displayed.getOrNull(selectedIdx)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(color = c.moneyIn, label = "Income")
                LegendDot(color = c.moneyOut, label = "Expenses")
            }
            if (selectedMonth != null) {
                Text(
                    text = selectedMonth.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (selectedMonth != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = c.surfaceSubtle,
                border = BorderStroke(1.dp, c.line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "In: ${HomeLogic.wholeText(selectedMonth.incomeMinor)}",
                        style = MaterialTheme.typography.labelMedium.tnum,
                        color = c.moneyIn,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Out: ${HomeLogic.wholeText(selectedMonth.expenseMinor)}",
                        style = MaterialTheme.typography.labelMedium.tnum,
                        color = c.moneyOut,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val net = selectedMonth.netMinor
                    val netText = if (net > 0L) "+${HomeLogic.wholeText(net)}" else HomeLogic.wholeText(net)
                    Text(
                        text = "Net: $netText",
                        style = MaterialTheme.typography.labelMedium.tnum,
                        color = if (net >= 0L) c.moneyIn else c.moneyOut,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val inColor = c.moneyIn
        val outColor = c.moneyOut
        val gridColor = c.line
        val activeBg = c.accentSoft

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
        ) {
            val count = displayed.size
            val groupW = size.width / count.toFloat()
            val barW = (groupW * 0.28f).coerceAtMost(22.dp.toPx())
            val pairGap = 4.dp.toPx()
            val topPad = 6.dp.toPx()
            val usableH = (size.height - topPad).coerceAtLeast(10f)

            // Horizontal reference lines
            listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { frac ->
                val y = topPad + usableH * (1f - frac)
                drawLine(
                    color = gridColor.copy(alpha = 0.65f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            // Baseline
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.2.dp.toPx(),
            )

            displayed.forEachIndexed { idx, m ->
                val centerX = groupW * idx + groupW / 2f
                if (idx == selectedIdx) {
                    drawRoundRect(
                        color = activeBg.copy(alpha = 0.55f),
                        topLeft = Offset(groupW * idx + 4.dp.toPx(), 0f),
                        size = Size((groupW - 8.dp.toPx()).coerceAtLeast(4f), size.height),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    )
                }

                val inH = ((max(0L, m.incomeMinor).toFloat() / maxMinor.toFloat()) * usableH)
                    .let { if (m.incomeMinor > 0L) max(4.dp.toPx(), it) else 0f }
                val outH = ((max(0L, m.expenseMinor).toFloat() / maxMinor.toFloat()) * usableH)
                    .let { if (m.expenseMinor > 0L) max(4.dp.toPx(), it) else 0f }

                if (inH > 0f) {
                    drawRoundRect(
                        color = inColor,
                        topLeft = Offset(centerX - pairGap / 2f - barW, size.height - inH),
                        size = Size(barW, inH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
                if (outH > 0f) {
                    drawRoundRect(
                        color = outColor,
                        topLeft = Offset(centerX + pairGap / 2f, size.height - outH),
                        size = Size(barW, outH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            displayed.forEachIndexed { idx, m ->
                val active = idx == selectedIdx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedIdx = idx },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = m.label.substringBefore(" "),
                        fontSize = 11.5.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) c.accent else c.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    val c = LedgerTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, color = c.muted, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RunwayGaugeBar(
    runway: RunwayAnalysis?,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val dangerColor = c.danger
    val warnColor = c.warn
    val accentColor = c.accent
    val okColor = c.ok
    val markerRing = c.surface

    val fraction = when {
        runway == null || runway.burnMonthsUsed.isEmpty() -> 0f
        runway.isCashFlowPositive -> 1.0f
        runway.runwayMonths != null -> (runway.runwayMonths / 18.0).coerceIn(0.02, 1.0).toFloat()
        else -> 0f
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
        ) {
            val barH = 10.dp.toPx()
            val yTop = (size.height - barH) / 2f
            val w = size.width
            // 4 safety zones: <3m (17%), 3-6m (17%), 6-12m (33%), 12m+ (33%)
            val segs = listOf(
                0f to 0.17f to dangerColor,
                0.17f to 0.34f to warnColor,
                0.34f to 0.67f to accentColor,
                0.67f to 1.0f to okColor,
            )
            segs.forEach { (range, col) ->
                val x0 = w * range.first
                val x1 = w * range.second
                drawRoundRect(
                    color = col.copy(alpha = 0.28f),
                    topLeft = Offset(x0 + 1.dp.toPx(), yTop),
                    size = Size((x1 - x0 - 2.dp.toPx()).coerceAtLeast(2f), barH),
                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                )
            }
            if (runway != null && runway.burnMonthsUsed.isNotEmpty()) {
                val fillW = (w * fraction).coerceIn(barH, w)
                val activeColor = when {
                    runway.isCashFlowPositive -> okColor
                    (runway.runwayMonths ?: 0.0) < 3.0 -> dangerColor
                    (runway.runwayMonths ?: 0.0) < 6.0 -> warnColor
                    (runway.runwayMonths ?: 0.0) < 12.0 -> accentColor
                    else -> okColor
                }
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, yTop),
                    size = Size(fillW, barH),
                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                )
                val cx = fillW.coerceIn(7.dp.toPx(), w - 7.dp.toPx())
                drawCircle(
                    color = markerRing,
                    radius = 7.dp.toPx(),
                    center = Offset(cx, size.height / 2f),
                )
                drawCircle(
                    color = activeColor,
                    radius = 4.5.dp.toPx(),
                    center = Offset(cx, size.height / 2f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("<3m", fontSize = 11.sp, color = c.danger, fontWeight = FontWeight.Medium)
            Text("3\u20136m", fontSize = 11.sp, color = c.warn, fontWeight = FontWeight.Medium)
            Text("6\u201312m", fontSize = 11.sp, color = c.accent, fontWeight = FontWeight.Medium)
            Text("12m+ / +ve", fontSize = 11.sp, color = c.ok, fontWeight = FontWeight.Medium)
        }
    }
}
