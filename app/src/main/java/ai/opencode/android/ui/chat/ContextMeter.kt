package ai.opencode.android.ui.chat

import ai.opencode.android.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** `1234` → `1.2K`, `1_234_567` → `1.23M`. */
fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.US, "%.0fK", value / 1_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

fun formatCost(usd: Double): String = when {
    usd <= 0.0 -> "$0"
    usd < 0.01 -> String.format(Locale.US, "$%.4f", usd)
    else -> String.format(Locale.US, "$%.2f", usd)
}

/** Green → amber → red as the window fills, like a fuel gauge. */
@Composable
private fun meterColor(fraction: Float?): Color = when {
    fraction == null -> MaterialTheme.colorScheme.primary
    fraction >= 0.9f -> MaterialTheme.colorScheme.error
    fraction >= 0.7f -> Color(0xFFE0A030)
    else -> MaterialTheme.colorScheme.primary
}

/**
 * Compact context gauge in the composer: a ring with the fill level plus
 * `used / limit · %` and the session cost. Tapping opens [UsageSheet].
 */
@Composable
fun ContextMeter(usage: ContextUsage?, onClick: () -> Unit) {
    usage ?: return
    val fraction = usage.fraction
    val color = meterColor(fraction)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 0f, 360f, useCenter = false, style = stroke)
                drawArc(color, -90f, 360f * (fraction ?: 0f), useCenter = false, style = stroke)
            }
            Spacer(Modifier.width(6.dp))
            val label = buildString {
                append(compactCount(usage.used))
                usage.limit?.let { append(" / ").append(compactCount(it)) }
                fraction?.let { append(" · ").append((it * 100).toInt()).append('%') }
                if (usage.cost > 0) append(" · ").append(formatCost(usage.cost))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSheet(usage: ContextUsage?, compacting: Boolean, onCompact: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.usage_title), style = MaterialTheme.typography.titleLarge)
            if (usage == null) {
                Text(
                    text = stringResource(R.string.usage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }
            usage.model?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(16.dp))

            val fraction = usage.fraction
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
                    Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, color = meterColor(it))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = meterColor(fraction),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                )
            } else {
                Text(stringResource(R.string.usage_limit_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            SegmentBar(usage)

            Spacer(Modifier.height(20.dp))
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
}

private val InputColor = Color(0xFF4C8DFF)
private val CacheColor = Color(0xFF35B57A)
private val CacheWriteColor = Color(0xFF8E6CF0)
private val OutputColor = Color(0xFFE0A030)
private val ReasoningColor = Color(0xFFE0607E)

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
