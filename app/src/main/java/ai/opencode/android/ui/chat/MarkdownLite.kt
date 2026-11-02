package ai.opencode.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Blocks produced by [MarkdownLite]. Deliberately tiny: opencode answers are markdown-ish, and a
 * full CommonMark renderer is a large dependency for a chat bubble.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock

    data class Code(val language: String?, val code: String) : MarkdownBlock
}

object MarkdownLite {

    private const val FENCE = "```"

    /** Splits markdown into paragraphs and fenced code blocks. Never throws. */
    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.isEmpty()) return emptyList()
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = StringBuilder()
        var inCode = false
        var codeLanguage: String? = null
        val code = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
            }
            paragraph.setLength(0)
        }

        for (line in markdown.split("\n")) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith(FENCE)) {
                if (inCode) {
                    blocks += MarkdownBlock.Code(codeLanguage, code.toString().trimEnd('\n'))
                    code.setLength(0)
                    inCode = false
                    codeLanguage = null
                } else {
                    flushParagraph()
                    inCode = true
                    codeLanguage = trimmed.removePrefix(FENCE).trim().ifEmpty { null }
                }
                continue
            }
            if (inCode) {
                code.append(line).append('\n')
            } else {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }

        if (inCode && code.isNotEmpty()) {
            // Unterminated fence: show what we have rather than swallowing the text.
            blocks += MarkdownBlock.Code(codeLanguage, code.toString().trimEnd('\n'))
        }
        flushParagraph()
        return blocks
    }
}

/** Renders **bold** and `inline code` spans; everything else is plain text. */
fun buildInlineMarkdown(text: String, codeColor: Color, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("**", index) -> {
                    val end = text.indexOf("**", index + 2)
                    if (end > index + 2) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                        append(text.substring(index + 2, end))
                        pop()
                        index = end + 2
                    } else {
                        append(text[index])
                        index++
                    }
                }

                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index + 1) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = codeColor,
                                background = codeBackground,
                                fontSize = 13.sp,
                            ),
                        )
                        append(text.substring(index + 1, end))
                        pop()
                        index = end + 1
                    } else {
                        append(text[index])
                        index++
                    }
                }

                else -> {
                    append(text[index])
                    index++
                }
            }
        }
    }

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val codeColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(
                    text = buildInlineMarkdown(block.text, codeColor, codeBackground),
                    style = style,
                )

                is MarkdownBlock.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        text = block.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
