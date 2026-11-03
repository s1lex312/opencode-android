package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.model.QuestionRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The `question` tool: the agent waits until each question is answered. Single-choice questions
 * answer on tap (and move to the next one); multi-choice and typed answers use the button.
 */
@Composable
fun QuestionPrompt(
    request: QuestionRequest,
    onAnswer: (List<List<String>>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = request.questions.size
    var index by remember(request.id) { mutableIntStateOf(0) }
    val selections = remember(request.id) { List(count) { mutableStateListOf<String>() } }
    val custom = remember(request.id) { mutableStateListOf(*Array(count) { "" }) }
    val question = request.questions[index.coerceIn(0, count - 1)]
    val last = index >= count - 1

    fun answers(): List<List<String>> = request.questions.indices.map { i ->
        selections[i].toList() + listOfNotNull(custom[i].trim().takeIf { it.isNotEmpty() })
    }

    fun advance() {
        if (last) onAnswer(answers()) else index++
    }

    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "? " + question.header.ifBlank { stringResource(R.string.question_title) },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (count > 1) {
                    Text(
                        text = "${index + 1}/$count",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(question.question, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.padding(top = 2.dp))
            question.options.forEachIndexed { i, option ->
                val picked = option.label in selections[index]
                QuestionOptionRow(
                    number = i + 1,
                    label = option.label,
                    description = option.description,
                    multiple = question.multiple,
                    picked = picked,
                    onClick = {
                        val current = selections[index]
                        if (question.multiple) {
                            if (picked) current.remove(option.label) else current.add(option.label)
                        } else {
                            current.clear()
                            current.add(option.label)
                            if (custom[index].isBlank()) advance()
                        }
                    },
                )
            }
            if (question.custom || question.options.isEmpty()) {
                OutlinedTextField(
                    value = custom[index],
                    onValueChange = { custom[index] = it },
                    label = { Text(stringResource(R.string.question_custom)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.question_dismiss)) }
                Spacer(Modifier.weight(1f))
                if (index > 0) TextButton(onClick = { index-- }) { Text(stringResource(R.string.question_back)) }
                val hasAnswer = selections[index].isNotEmpty() || custom[index].isNotBlank()
                TextButton(onClick = ::advance, enabled = hasAnswer) {
                    Text(stringResource(if (last) R.string.question_send else R.string.question_next))
                }
            }
        }
    }
}

@Composable
private fun QuestionOptionRow(
    number: Int,
    label: String,
    description: String,
    multiple: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiple) Checkbox(checked = picked, onCheckedChange = { onClick() }) else RadioButton(selected = picked, onClick = onClick)
        Text(
            text = "$number.",
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

