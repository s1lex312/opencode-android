package ai.opencode.android.ui.tools

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.chat.compactCount
import ai.opencode.android.ui.chat.formatCost
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsSwitchItem
import ai.opencode.android.ui.config.SliderRow
import ai.opencode.android.ui.settings.SettingsPage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.format.DateTimeFormatter

/** Spending per day and model, plus the budget that warns or blocks prompts. */
@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val summary = state.summary

    SettingsPage(title = stringResource(R.string.stats_title), onBack = onBack) {
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text(
                stringResource(R.string.stats_loading, state.done, state.total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(stringResource(R.string.stats_today), formatCost(summary.today), budget.daily.takeIf { it > 0 }?.let { summary.today / it }, Modifier.weight(1f))
            StatCard(stringResource(R.string.stats_week), formatCost(summary.week), null, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(stringResource(R.string.stats_month), formatCost(summary.month), budget.monthly.takeIf { it > 0 }?.let { summary.month / it }, Modifier.weight(1f))
            StatCard(stringResource(R.string.stats_total), formatCost(summary.total), null, Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.stats_totals, summary.turns, compactCount(summary.tokens)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )

        SettingsGroup(title = stringResource(R.string.stats_by_day)) {
            DayChart(summary.byDay.map { it.second }, Modifier.fillMaxWidth().height(140.dp).padding(16.dp))
            val formatter = remember { DateTimeFormatter.ofPattern("d MMM") }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                summary.byDay.firstOrNull()?.let { Text(it.first.format(formatter), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)) }
                summary.byDay.lastOrNull()?.let { Text(it.first.format(formatter), style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.stats_refresh))
            }
        }

        if (summary.byModel.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.stats_by_model)) {
                val max = summary.byModel.maxOf { it.cost }.takeIf { it > 0 } ?: 1.0
                summary.byModel.forEachIndexed { index, model ->
                    if (index > 0) SettingsDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                model.model,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(formatCost(model.cost), style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(
                            progress = { (model.cost / max).toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        Text(
                            stringResource(R.string.stats_model_line, model.turns, compactCount(model.tokens)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsGroup(title = stringResource(R.string.budget_title)) {
            BudgetField(stringResource(R.string.budget_daily), budget.daily) { value -> viewModel.setBudget { it.copy(daily = value) } }
            SettingsDivider()
            BudgetField(stringResource(R.string.budget_monthly), budget.monthly) { value -> viewModel.setBudget { it.copy(monthly = value) } }
            SettingsDivider()
            SliderRow(
                title = stringResource(R.string.budget_warn),
                value = budget.warnPercent,
                range = 50..100,
                label = { "$it%" },
                onChange = { value -> viewModel.setBudget { it.copy(warnPercent = value) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.budget_block),
                subtitle = stringResource(R.string.budget_block_hint),
                checked = budget.block,
                onCheckedChange = { value -> viewModel.setBudget { it.copy(block = value) } },
            )
        }
        Text(
            stringResource(R.string.stats_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun StatCard(title: String, value: String, ratio: Double?, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            if (ratio != null) {
                LinearProgressIndicator(
                    progress = { ratio.toFloat().coerceIn(0f, 1f) },
                    color = if (ratio >= 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DayChart(values: List<Double>, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().takeIf { it > 0 } ?: 1.0
        val slot = size.width / values.size
        val width = (slot * 0.7f).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            val x = index * slot + (slot - width) / 2
            drawRoundRect(track, Offset(x, 0f), Size(width, size.height), CornerRadius(3f, 3f))
            val height = (value / max).toFloat() * size.height
            if (height > 0f) drawRoundRect(bar, Offset(x, size.height - height), Size(width, height), CornerRadius(3f, 3f))
        }
    }
}

@Composable
private fun BudgetField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value > 0) value.toString().removeSuffix(".0") else "") }
    LaunchedEffect(text) {
        val parsed = text.replace(',', '.').toDoubleOrNull() ?: 0.0
        if (parsed != value && (text.isEmpty() || parsed > 0)) onChange(parsed.coerceAtLeast(0.0))
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input -> text = input.filter { it.isDigit() || it == '.' || it == ',' }.take(10) },
        label = { Text(label) },
        prefix = { Text("$") },
        placeholder = { Text(stringResource(R.string.budget_none)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

