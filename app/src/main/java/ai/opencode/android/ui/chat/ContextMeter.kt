package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.settings.ChatSettings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

/** `1234` → `1.2K`, `1_234_567` → `1.23M`. */
fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0).replace(".00M", "M")
    value >= 10_000 -> String.format(Locale.US, "%.0fK", value / 1_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

fun formatCost(usd: Double): String = when {
    usd <= 0.0 -> "$0"
    usd < 0.01 -> String.format(Locale.US, "$%.4f", usd)
    else -> String.format(Locale.US, "$%.2f", usd)
}

/** Window sizes offered as one-tap presets. */
private val PRESETS = listOf(32_000L, 64_000L, 128_000L, 200_000L, 256_000L, 400_000L, 1_000_000L, 2_000_000L)

/** Which override the editor dialog is changing. */
private enum class LimitTarget { CHAT, MODEL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSheet(
    usage: ContextUsage?,
    compacting: Boolean,
    chat: ChatSettings,
    sessionLimit: Long?,
    modelLimit: Long?,
    canWriteServerConfig: Boolean,
    onCompact: () -> Unit,
    onSetChatLimit: (Long?) -> Unit,
    onSetModelLimit: (modelKey: String, tokens: Long?, applyToServer: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<LimitTarget?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.usage_title), style = MaterialTheme.typography.titleLarge)
            usage?.modelKey?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(16.dp))

            if (usage != null) {
                val fraction = usage.fraction
                val color = gaugeColor(fraction, chat.contextWarnPercent, chat.contextDangerPercent)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(compactCount(usage.used), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = usage.limit?.let { " / " + compactCount(it) } ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    fraction?.let {
                        Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, color = color)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                    )
                    usage.percentLeft?.let { left ->
                        Text(
                            text = stringResource(R.string.usage_left, left),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    Text(stringResource(R.string.usage_limit_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                SegmentBar(usage)
            } else {
                Text(
                    text = stringResource(R.string.usage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- context window overrides
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Text(stringResource(R.string.usage_window_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.usage_window_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            LimitRow(
                title = stringResource(R.string.usage_window_chat),
                value = sessionLimit?.let(::compactCount) ?: stringResource(R.string.usage_window_inherit_model),
                active = usage?.limitSource == LimitSource.CHAT,
                onClick = { editing = LimitTarget.CHAT },
            )
            if (usage?.modelKey != null) {
                LimitRow(
                    title = stringResource(R.string.usage_window_model, usage.modelKey.substringAfter('/')),
                    value = modelLimit?.let(::compactCount)
                        ?: usage.catalogueLimit?.let { stringResource(R.string.usage_window_catalogue, compactCount(it)) }
                        ?: stringResource(R.string.usage_window_unknown),
                    active = usage.limitSource == LimitSource.MODEL,
                    onClick = { editing = LimitTarget.MODEL },
                )
            }

            if (usage != null) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.usage_last_turn), style = MaterialTheme.typography.titleSmall)
                UsageRow(stringResource(R.string.usage_input), usage.last.input, InputColor)
                UsageRow(stringResource(R.string.usage_cache_read), usage.last.cacheRead, CacheColor)
                UsageRow(stringResource(R.string.usage_cache_write), usage.last.cacheWrite, CacheWriteColor)
                UsageRow(stringResource(R.string.usage_output), usage.last.output, OutputColor)
                UsageRow(stringResource(R.string.usage_reasoning), usage.last.reasoning, ReasoningColor)

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.usage_session), style = MaterialTheme.typography.titleSmall)
                val total = usage.total
                UsageRow(stringResource(R.string.usage_total_in), total.input + total.cacheRead + total.cacheWrite, null)
                UsageRow(stringResource(R.string.usage_total_out), total.output + total.reasoning, null)
                if (total.cacheRead > 0 && total.input + total.cacheRead > 0) {
                    val hit = total.cacheRead * 100 / (total.input + total.cacheRead)
                    ValueRow(stringResource(R.string.usage_cache_hit), "$hit%")
                }
                ValueRow(stringResource(R.string.usage_cost), formatCost(usage.cost))
            }

            Spacer(Modifier.height(20.dp))
            Button(onClick = onCompact, enabled = !compacting, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (compacting) R.string.usage_compacting else R.string.usage_compact))
            }
            Text(
                text = stringResource(R.string.usage_compact_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
        }
    }

    when (editing) {
        LimitTarget.CHAT -> LimitDialog(
            title = stringResource(R.string.usage_window_chat),
            current = sessionLimit,
            serverOption = false,
            onSave = { tokens, _ ->
                onSetChatLimit(tokens)
                editing = null
            },
            onDismiss = { editing = null },
        )

        LimitTarget.MODEL -> usage?.modelKey?.let { key ->
            LimitDialog(
                title = stringResource(R.string.usage_window_model, key.substringAfter('/')),
                current = modelLimit ?: usage.catalogueLimit,
                serverOption = canWriteServerConfig,
                onSave = { tokens, toServer ->
                    onSetModelLimit(key, tokens, toServer)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }

        null -> Unit
    }
}

@Composable
private fun LimitRow(title: String, value: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** Presets plus free input (`128k`, `1.5m`, `200000`); "Reset" removes the override. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitDialog(
    title: String,
    current: Long?,
    serverOption: Boolean,
    onSave: (tokens: Long?, applyToServer: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current?.let(::compactCount)?.lowercase().orEmpty()) }
    var toServer by remember { mutableStateOf(false) }
    val parsed = parseTokenCount(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.forEach { preset ->
                        FilterChip(
                            selected = parsed == preset,
                            onClick = { text = compactCount(preset).lowercase() },
                            label = { Text(compactCount(preset), fontFamily = FontFamily.Monospace) },
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.usage_window_tokens)) },
                    placeholder = { Text("128k") },
                    singleLine = true,
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = { Text(parsed?.let { "%,d".format(Locale.US, it) } ?: stringResource(R.string.usage_window_format)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (serverOption) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { toServer = !toServer },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = toServer, onCheckedChange = { toServer = it })
                        Text(stringResource(R.string.usage_window_server), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parsed, toServer) }, enabled = parsed != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null, false) }) { Text(stringResource(R.string.usage_window_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

private val InputColor: Color
    @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
    get() = ai.opencode.android.ui.theme.LocalExtraColors.current.contextInput
private val CacheColor: Color
    @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
    get() = ai.opencode.android.ui.theme.LocalExtraColors.current.contextCacheRead
private val CacheWriteColor: Color
    @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
    get() = ai.opencode.android.ui.theme.LocalExtraColors.current.contextCacheWrite
private val OutputColor: Color
    @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
    get() = ai.opencode.android.ui.theme.LocalExtraColors.current.contextOutput
private val ReasoningColor: Color
    @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
    get() = ai.opencode.android.ui.theme.LocalExtraColors.current.contextReasoning

/** Stacked bar of what the context is made of: fresh input, cache, output, reasoning. */
@Composable
private fun SegmentBar(usage: ContextUsage) {
    val last = usage.last
    val parts = listOf(
        last.input to InputColor,
        last.cacheRead to CacheColor,
        last.cacheWrite to CacheWriteColor,
        last.output to OutputColor,
        last.reasoning to ReasoningColor,
    ).filter { it.first > 0 }
    if (parts.isEmpty()) return
    val sum = parts.sumOf { it.first }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(6.dp)) {
        parts.forEach { (value, color) ->
            Box(
                Modifier
                    .weight(value.toFloat() / sum)
                    .height(6.dp)
                    .padding(end = 2.dp)
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun UsageRow(label: String, value: Long, color: Color?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (color != null) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(compactCount(value), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
