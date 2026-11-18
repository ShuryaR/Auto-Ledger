package app.onebook.autoledger.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TxnKind
import app.onebook.autoledger.inbox.ChipTone
import app.onebook.autoledger.inbox.ChipView
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.inbox.InboxKind
import app.onebook.autoledger.inbox.InboxRow
import app.onebook.autoledger.state.ActivitySort
import app.onebook.autoledger.state.ActivityStatus
import app.onebook.autoledger.state.AppBootState
import app.onebook.autoledger.state.BackfillOption
import app.onebook.autoledger.state.RowStatus
import app.onebook.autoledger.state.SetupLogic
import app.onebook.autoledger.state.StatusTone
import app.onebook.autoledger.ui.theme.LedgerTheme
import app.onebook.autoledger.ui.theme.LocalBottomBarClearance
import app.onebook.autoledger.ui.theme.tnum

enum class BannerTone { INFO, OK, WARN, ERROR }

enum class ButtonVariant { PRIMARY, SECONDARY, DANGER, QUIET }

enum class ChoiceRole { RADIO, CHECKBOX }

enum class FilterMode { ACTIVITY, INBOX }

data class CombinedFilters(
    val direction: Direction? = null,
    val accountLast4: String? = null,
    val status: ActivityStatus? = null,
    val kind: InboxKind? = null,
    val sort: ActivitySort = ActivitySort.NEWEST,
) {
    fun activeCount(mode: FilterMode): Int =
        (if (direction != null) 1 else 0) +
            (if (accountLast4 != null) 1 else 0) +
            (if (mode == FilterMode.ACTIVITY && status != null) 1 else 0) +
            (if (mode == FilterMode.INBOX && kind != null) 1 else 0) +
            (if (mode == FilterMode.ACTIVITY && sort != ActivitySort.NEWEST) 1 else 0)
}

fun openExternalUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        // Ignore if no browser is installed
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    modifier: Modifier = Modifier,
    top: (@Composable () -> Unit)? = null,
    title: String? = null,
    subtitle: String? = null,
    footer: (@Composable () -> Unit)? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    applySystemBarPadding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LedgerTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.paper)
            .let { if (applySystemBarPadding) it.statusBarsPadding() else it }
            .imePadding(),
    ) {
        val body: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                if (top != null) {
                    top()
                    Spacer(Modifier.height(12.dp))
                }
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted,
                    )
                    Spacer(Modifier.height(16.dp))
                } else if (title != null) {
                    Spacer(Modifier.height(12.dp))
                }
                content()
                val bottomClearance = if (footer == null) LocalBottomBarClearance.current else 0.dp
                Spacer(
                    Modifier
                        .height(24.dp + bottomClearance)
                        .let { if (!applySystemBarPadding && bottomClearance > 0.dp) it.navigationBarsPadding() else it },
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (onRefresh != null) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    body()
                }
            } else {
                body()
            }
        }

        if (footer != null) {
            Surface(
                color = c.surface,
                border = BorderStroke(1.dp, c.line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (applySystemBarPadding) it.navigationBarsPadding() else it }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    footer()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerLazyScreen(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    headerExtra: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    applySystemBarPadding: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val c = LedgerTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.paper)
            .let { if (applySystemBarPadding) it.statusBarsPadding() else it },
    ) {
        val listBlock: @Composable () -> Unit = {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            ) {
                if (title != null || subtitle != null || headerExtra != null) {
                    item(key = "__screen_header__") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (title != null) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = c.ink,
                                )
                            }
                            if (subtitle != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.muted,
                                )
                            }
                            if (headerExtra != null) {
                                Spacer(Modifier.height(12.dp))
                                headerExtra()
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                content()
                item(key = "__bottom_spacer__") {
                    val bottomClearance = if (footer == null) LocalBottomBarClearance.current else 0.dp
                    Spacer(
                        Modifier
                            .height(24.dp + bottomClearance)
                            .let { if (!applySystemBarPadding && bottomClearance > 0.dp) it.navigationBarsPadding() else it },
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (onRefresh != null) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    listBlock()
                }
            } else {
                listBlock()
            }
        }

        if (footer != null) {
            Surface(
                color = c.surface,
                border = BorderStroke(1.dp, c.line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (applySystemBarPadding) it.navigationBarsPadding() else it }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    footer()
                }
            }
        }
    }
}

@Composable
fun StepHeader(step: Int, total: Int, label: String, onBack: (() -> Unit)? = null) {
    val c = LedgerTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                BackLink(onPress = onBack)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "Step $step of $total",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = c.muted,
            )
        }
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.accent,
        )
    }
}

@Composable
fun BackLink(label: String = "Back", onPress: () -> Unit) {
    val c = LedgerTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPress)
            .heightIn(min = 48.dp)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = label,
            tint = c.accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.accent,
        )
    }
}

@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    accentBorder: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LedgerTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .let { if (onClick != null) it.clip(shape).clickable(onClick = onClick) else it },
        shape = shape,
        color = c.surface,
        border = BorderStroke(if (accentBorder) 1.5.dp else 1.dp, if (accentBorder) c.accent else c.line),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun LedgerBanner(
    message: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.INFO,
    title: String? = null,
    compact: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = LedgerTheme.colors
    val (bg, fg, icon) = when (tone) {
        BannerTone.INFO -> Triple(c.accentSoft, c.accent, Icons.Rounded.Info)
        BannerTone.OK -> Triple(c.okSoft, c.ok, Icons.Rounded.CheckCircle)
        BannerTone.WARN -> Triple(c.warnSoft, c.warn, Icons.Rounded.WarningAmber)
        BannerTone.ERROR -> Triple(c.dangerSoft, c.danger, Icons.Rounded.ErrorOutline)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.2f)),
    ) {
        Column(modifier = Modifier.padding(if (compact) 12.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.padding(top = 2.dp).size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = fg,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        text = message,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        color = c.ink,
                    )
                }
            }
            if (actionLabel != null && onAction != null || secondaryLabel != null && onSecondary != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (actionLabel != null && onAction != null) {
                        OutlinedButton(
                            onClick = onAction,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, fg),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
                        ) {
                            Text(actionLabel, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        }
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        TextButton(
                            onClick = onSecondary,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = c.ink),
                        ) {
                            Text(secondaryLabel, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LedgerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = LedgerTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val isClickable = enabled && !loading
    when (variant) {
        ButtonVariant.PRIMARY -> Button(
            onClick = onClick,
            enabled = isClickable,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = c.accent,
                contentColor = if (c.isDark) Color(0xFF0B111D) else Color.White,
                disabledContainerColor = c.accent.copy(alpha = 0.4f),
                disabledContentColor = (if (c.isDark) Color(0xFF0B111D) else Color.White).copy(alpha = 0.8f),
            ),
        ) {
            ButtonBody(label, loading)
        }
        ButtonVariant.SECONDARY -> OutlinedButton(
            onClick = onClick,
            enabled = isClickable,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = shape,
            border = BorderStroke(1.dp, c.line),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = c.surface,
                contentColor = c.ink,
                disabledContentColor = c.muted,
            ),
        ) {
            ButtonBody(label, loading)
        }
        ButtonVariant.DANGER -> Button(
            onClick = onClick,
            enabled = isClickable,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = c.danger,
                contentColor = if (c.isDark) Color(0xFF0B111D) else Color.White,
                disabledContainerColor = c.danger.copy(alpha = 0.4f),
            ),
        ) {
            ButtonBody(label, loading)
        }
        ButtonVariant.QUIET -> TextButton(
            onClick = onClick,
            enabled = isClickable,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = shape,
            colors = ButtonDefaults.textButtonColors(
                contentColor = c.accent,
                disabledContentColor = c.muted,
            ),
        ) {
            ButtonBody(label, loading)
        }
    }
}

@Composable
private fun ButtonBody(label: String, loading: Boolean) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
    }
    Text(
        text = label,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun LedgerTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    error: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = LedgerTheme.colors
    var revealed by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = c.ink,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = singleLine,
            isError = error != null,
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, color = c.muted) }
            } else null,
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (secret) {
                {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (revealed) "Hide" else "Show",
                            tint = c.muted,
                        )
                    }
                }
            } else null,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surface,
                unfocusedContainerColor = c.surface,
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.line,
                errorBorderColor = c.danger,
                focusedTextColor = c.ink,
                unfocusedTextColor = c.ink,
            ),
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = error, fontSize = 12.5.sp, color = c.danger)
        } else if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = hint, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.muted)
        }
    }
}

@Composable
fun LedgerSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = c.muted)
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = c.muted)
                }
            }
        } else null,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surface,
            unfocusedContainerColor = c.surface,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.line,
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
        ),
    )
}

@Composable
fun LedgerChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    role: ChoiceRole = ChoiceRole.RADIO,
    disabled: Boolean = false,
) {
    val c = LedgerTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .clickable(enabled = !disabled, onClick = onClick),
        shape = shape,
        color = if (selected) c.accentSoft else c.surface,
        border = BorderStroke(1.dp, if (selected) c.accent else c.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val indColor = when {
                disabled -> c.line
                selected -> c.accent
                else -> c.muted
            }
            if (role == ChoiceRole.RADIO) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(2.dp, indColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(c.accent, CircleShape),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (selected) c.accent else Color.Transparent)
                        .border(2.dp, indColor, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = if (c.isDark) Color(0xFF0B111D) else Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (disabled) c.muted else c.ink,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = c.muted,
                    )
                }
            }
        }
    }
}

@Composable
fun LedgerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) c.accentSoft else c.surface,
        border = BorderStroke(1.dp, if (selected) c.accent else c.line),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) c.accent else c.ink,
            )
        }
    }
}

@Composable
fun LedgerBadgeChip(
    text: String,
    tone: ChipTone,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val (bg, fg) = when (tone) {
        ChipTone.ACCENT -> c.accentSoft to c.accent
        ChipTone.WARN -> c.warnSoft to c.warn
        ChipTone.PLAIN -> c.paper to c.muted
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = bg,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun LedgerStatusChip(
    status: RowStatus,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val (bg, fg) = when (status.tone) {
        StatusTone.OK -> c.okSoft to c.ok
        StatusTone.INFO -> c.accentSoft to c.accent
        StatusTone.WARN -> c.warnSoft to c.warn
        StatusTone.ERROR -> c.dangerSoft to c.danger
        StatusTone.NEUTRAL -> c.paper to c.muted
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = bg,
    ) {
        Text(
            text = status.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun LedgerFigure(
    value: String,
    label: String? = null,
    note: String? = null,
    valueColor: Color? = null,
) {
    val c = LedgerTheme.colors
    Column {
        if (!label.isNullOrBlank()) {
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge.tnum,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: c.ink,
        )
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = note, fontSize = 13.sp, color = c.muted)
        }
    }
}

@Composable
fun LedgerDetailCard(
    txn: PendingTxn,
    today: String,
    accountLabel: String,
) {
    val c = LedgerTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val isIn = txn.direction == Direction.IN
    val amtColor = if (isIn) c.moneyIn else c.moneyOut

    LedgerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Formatters.titleOf(txn),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOf(
                        Formatters.kindLabel(txn.kind),
                        accountLabel,
                        Formatters.dateTimeLabel(txn),
                    ).joinToString(" \u00B7 "),
                    fontSize = 13.5.sp,
                    color = c.muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = Formatters.signedAmount(txn),
                style = MaterialTheme.typography.titleLarge.tnum,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = amtColor,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "Hide details" else "More details",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.accent,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(18.dp),
            )
        }

        if (expanded) {
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
            DetailLine("From bank message", txn.sender)
            if (!txn.utr.isNullOrBlank()) {
                DetailLine("Bank reference", txn.utr, mono = true)
            }
            if (!txn.merchant.isNullOrBlank()) {
                DetailLine("Merchant", txn.merchant)
            }
            if (!txn.counterparty.isNullOrBlank()) {
                DetailLine("Counterparty", txn.counterparty)
            }
            if (txn.balanceMinor != null) {
                DetailLine("Balance after", Formatters.formatInr(txn.balanceMinor))
            }
            if (txn.mergedCount > 1) {
                DetailLine("Combined messages", "${txn.mergedCount} alerts matched as one transaction")
            }
        }
    }
}

@Composable
fun DetailLine(label: String, value: String, mono: Boolean = false, strong: Boolean = false) {
    val c = LedgerTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = c.muted,
            modifier = Modifier.weight(0.42f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 13.5.sp,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = c.ink,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
fun LedgerTxnRow(
    row: InboxRow,
    chip: ChipView,
    onClick: () -> Unit,
) {
    val c = LedgerTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val icon = when (row.kindIcon) {
        InboxKind.BANK -> Icons.Rounded.AccountBalance
        InboxKind.UPI -> Icons.Rounded.QrCode2
        InboxKind.CARD -> Icons.Rounded.CreditCard
    }
    val isIn = row.direction == Direction.IN
    val amtColor = if (isIn) c.moneyIn else c.moneyOut
    val badgeBg = if (isIn) c.moneyInSoft else c.moneyOutSoft

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = c.surface,
        border = BorderStroke(1.dp, c.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = amtColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.subtitle,
                    fontSize = 12.5.sp,
                    color = c.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LedgerBadgeChip(text = chip.text, tone = chip.tone)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = row.amountText,
                style = MaterialTheme.typography.titleMedium.tnum,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = amtColor,
            )
        }
    }
}

@Composable
fun LedgerToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    val c = LedgerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.ink)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun LedgerEmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = LedgerTheme.colors
    LedgerCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = c.muted,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                LedgerButton(label = actionLabel, onClick = onAction, variant = ButtonVariant.SECONDARY)
            }
        }
    }
}

@Composable
fun LedgerSuggestionCard(
    title: String,
    subtitle: String,
    actionLabel: String = "Use this answer",
    onClick: () -> Unit,
) {
    val c = LedgerTheme.colors
    LedgerCard(accentBorder = true, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
                Spacer(Modifier.height(6.dp))
                Text(actionLabel, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.accent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackfillChoiceSection(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
    options: List<BackfillOption> = SetupLogic.BACKFILL_OPTIONS,
    note: String? = null,
) {
    val c = LedgerTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "How far back to read",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.ink,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow {
            options.forEach { opt ->
                LedgerChip(
                    label = opt.label,
                    selected = selectedDays == opt.days,
                    onClick = { onSelect(opt.days) },
                )
            }
        }
        if (!note.isNullOrBlank()) {
            Text(text = note, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheetDialog(
    visible: Boolean,
    value: CombinedFilters,
    accounts: List<String>,
    mode: FilterMode,
    onApply: (CombinedFilters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val c = LedgerTheme.colors
    var draft by remember(value) { mutableStateOf(value) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Filters", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.ink)
            Spacer(Modifier.height(12.dp))

            FilterGroupHeader("Money")
            FlowRow {
                LedgerChip("All", draft.direction == null, { draft = draft.copy(direction = null) })
                LedgerChip("Money in", draft.direction == Direction.IN, { draft = draft.copy(direction = Direction.IN) })
                LedgerChip("Money out", draft.direction == Direction.OUT, { draft = draft.copy(direction = Direction.OUT) })
            }

            if (mode == FilterMode.INBOX) {
                FilterGroupHeader("How it was paid")
                FlowRow {
                    LedgerChip("All", draft.kind == null, { draft = draft.copy(kind = null) })
                    LedgerChip("Bank", draft.kind == InboxKind.BANK, { draft = draft.copy(kind = InboxKind.BANK) })
                    LedgerChip("UPI", draft.kind == InboxKind.UPI, { draft = draft.copy(kind = InboxKind.UPI) })
                    LedgerChip("Card", draft.kind == InboxKind.CARD, { draft = draft.copy(kind = InboxKind.CARD) })
                }
            } else {
                FilterGroupHeader("Status")
                FlowRow {
                    LedgerChip("All", draft.status == null, { draft = draft.copy(status = null) })
                    LedgerChip("Waiting for your accountant", draft.status == ActivityStatus.WAITING, { draft = draft.copy(status = ActivityStatus.WAITING) })
                    LedgerChip("Submitted", draft.status == ActivityStatus.SUBMITTED, { draft = draft.copy(status = ActivityStatus.SUBMITTED) })
                    LedgerChip("Ignored", draft.status == ActivityStatus.IGNORED, { draft = draft.copy(status = ActivityStatus.IGNORED) })
                }
            }

            if (accounts.size > 1) {
                FilterGroupHeader("Account")
                FlowRow {
                    LedgerChip("All accounts", draft.accountLast4 == null, { draft = draft.copy(accountLast4 = null) })
                    accounts.forEach { last4 ->
                        LedgerChip(
                            label = "Account ending $last4",
                            selected = draft.accountLast4 == last4,
                            onClick = { draft = draft.copy(accountLast4 = last4) },
                        )
                    }
                }
            }

            if (mode == FilterMode.ACTIVITY) {
                FilterGroupHeader("Sort by")
                val sorts = listOf(
                    ActivitySort.NEWEST to "Newest first",
                    ActivitySort.OLDEST to "Oldest first",
                    ActivitySort.AMOUNT_HIGH to "Highest amount",
                    ActivitySort.AMOUNT_LOW to "Lowest amount",
                    ActivitySort.NAME_AZ to "Name A to Z",
                )
                FlowRow {
                    sorts.forEach { (key, label) ->
                        LedgerChip(label, draft.sort == key, { draft = draft.copy(sort = key) })
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LedgerButton(
                    label = "Reset",
                    onClick = onReset,
                    variant = ButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
                LedgerButton(
                    label = "Apply",
                    onClick = { onApply(draft) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FilterGroupHeader(title: String) {
    val c = LedgerTheme.colors
    Text(
        text = title,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = c.muted,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}

@Composable
fun LedgerConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val c = LedgerTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.ink,
        textContentColor = c.muted,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message, fontSize = 14.5.sp, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) c.danger else c.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelLabel, color = c.ink)
            }
        },
    )
}

@Composable
fun AppBanners(
    bootState: AppBootState,
    onSignInAgain: () -> Unit,
) {
    if (!bootState.needsLogin && !bootState.engineUnusable) return
    val c = LedgerTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.paper)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        if (bootState.engineUnusable) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                title = "SMS reading is unavailable, update the app",
                message = "New bank messages cannot be read until the app is updated. Entries you already have are safe.",
            )
        }
        if (bootState.needsLogin) {
            LedgerBanner(
                tone = BannerTone.WARN,
                title = "Sign in again",
                message = "ERPNext no longer accepts the saved login, so entries are waiting on this phone. Everything stays here until you sign in.",
                actionLabel = "Sign in again",
                onAction = onSignInAgain,
            )
        }
    }
}

@Composable
fun ExternalOpenButton(label: String, onClick: () -> Unit) {
    val c = LedgerTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
    ) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
