package ai.opencode.android.ui.chat

import ai.opencode.android.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Block structure produced by [MarkdownLite]: the CommonMark/GFM subset LLM answers use. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock

    data class Code(val language: String?, val code: String) : MarkdownBlock

    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MarkdownBlock

    data class Table(val header: List<String>, val align: List<Align>, val rows: List<List<String>>) : MarkdownBlock

    data object Rule : MarkdownBlock

    enum class Align { START, CENTER, END }
}

/** One list entry; [checked] is set for GFM task items (`- [ ]` / `- [x]`). */
data class ListItem(val blocks: List<MarkdownBlock>, val checked: Boolean? = null)

object MarkdownLite {

    private val ATX = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val RULE = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val SETEXT = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
    private val FENCE = Regex("""^( {0,3})(`{3,}|~{3,})[ \t]*([^`\s]*)?.*$""")
    private val BULLET = Regex("""^( {0,12})([-*+])([ \t]+)(.*)$""")
    private val ORDERED = Regex("""^( {0,12})(\d{1,9})([.)])([ \t]+)(.*)$""")
    private val TABLE_DELIMITER = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
    private val TASK = Regex("""^\[([ xX])][ \t]+(.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** Never throws; unknown syntax degrades to paragraphs. */
    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.isBlank()) return emptyList()
        return parseLines(markdown.replace("\r\n", "\n").replace('\t', ' ').split("\n"))
    }

    private fun parseLines(lines: List<String>): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        var index = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n") { it.trim() })
                paragraph.clear()
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()

            if (trimmed.isEmpty()) {
                flushParagraph()
                index++
                continue
            }

            // Setext heading: the paragraph above becomes an H1/H2.
            if (paragraph.isNotEmpty() && SETEXT.matches(line)) {
                val level = if (trimmed.startsWith("=")) 1 else 2
                blocks += MarkdownBlock.Heading(level, paragraph.joinToString(" ") { it.trim() })
                paragraph.clear()
                index++
                continue
            }

            val fenceMatch = FENCE.matchEntire(line)
            if (fenceMatch != null) {
                flushParagraph()
                val indent = fenceMatch.groupValues[1].length
                val fence = fenceMatch.groupValues[2]
                val language = fenceMatch.groupValues[3].ifBlank { null }
                val code = StringBuilder()
                index++
                while (index < lines.size) {
                    val candidate = lines[index].trimStart()
                    if (candidate.startsWith(fence) && candidate.trimEnd().all { it == fence[0] }) {
                        index++
                        break
                    }
                    val leading = lines[index].length - lines[index].trimStart().length
                    code.append(lines[index].drop(minOf(indent, leading))).append('\n')
                    index++
                }
                blocks += MarkdownBlock.Code(language, code.toString().trimEnd('\n'))
                continue
            }

            val heading = ATX.matchEntire(line)
            if (heading != null) {
                flushParagraph()
                blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                index++
                continue
            }

            if (RULE.matches(line)) {
                flushParagraph()
                blocks += MarkdownBlock.Rule
                index++
                continue
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quoted = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quoted += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                    index++
                }
                blocks += MarkdownBlock.Quote(parseLines(quoted))
                continue
            }

            if (isTableStart(lines, index)) {
                flushParagraph()
                val header = cells(lines[index])
                val align = cells(lines[index + 1]).map { cell ->
                    val c = cell.trim()
                    when {
                        c.startsWith(":") && c.endsWith(":") -> MarkdownBlock.Align.CENTER
                        c.endsWith(":") -> MarkdownBlock.Align.END
                        else -> MarkdownBlock.Align.START
                    }
                }
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
                    val row = cells(lines[index])
                    rows += List(header.size) { column -> row.getOrElse(column) { "" } }
                    index++
                }
                blocks += MarkdownBlock.Table(header, List(header.size) { align.getOrElse(it) { MarkdownBlock.Align.START } }, rows)
                continue
            }

            if (listMarker(line) != null && (paragraph.isEmpty() || startsListInsideParagraph(line))) {
                flushParagraph()
                index = parseList(lines, index, blocks)
                continue
            }

            paragraph += line
            index++
        }
        flushParagraph()
        return blocks
    }

    private data class Marker(val indent: Int, val contentIndent: Int, val ordered: Boolean, val number: Int, val content: String)

    private fun listMarker(line: String): Marker? {
        BULLET.matchEntire(line)?.let { m ->
            val indent = m.groupValues[1].length
            return Marker(indent, indent + 1 + m.groupValues[3].length.coerceAtMost(4), false, 0, m.groupValues[4])
        }
        ORDERED.matchEntire(line)?.let { m ->
            val indent = m.groupValues[1].length
            val width = m.groupValues[2].length + 1
            return Marker(indent, indent + width + m.groupValues[4].length.coerceAtMost(4), true, m.groupValues[2].toIntOrNull() ?: 1, m.groupValues[5])
        }
        return null
    }

    /** CommonMark: only `-`/`*`/`+` and `1.` may interrupt a paragraph. */
    private fun startsListInsideParagraph(line: String): Boolean {
        val marker = listMarker(line) ?: return false
        return !marker.ordered || marker.number == 1
    }

    private fun parseList(lines: List<String>, from: Int, out: MutableList<MarkdownBlock>): Int {
        val first = listMarker(lines[from]) ?: return from + 1
        val items = mutableListOf<ListItem>()
        var index = from
        while (index < lines.size) {
            val marker = listMarker(lines[index])
            if (marker == null || marker.ordered != first.ordered || marker.indent > first.indent + 1 || marker.indent < first.indent) break
            val body = mutableListOf(marker.content)
            index++
            while (index < lines.size) {
                val line = lines[index]
                if (line.isBlank()) {
                    // A blank line continues the item only if indented content follows.
                    val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() } ?: break
                    val nextIndent = next.length - next.trimStart().length
                    if (nextIndent >= marker.contentIndent) {
                        body += ""
                        index++
                        continue
                    }
                    break
                }
                val indent = line.length - line.trimStart().length
                val sibling = listMarker(line)
                if (sibling != null && indent <= first.indent + 1) break
                if (indent >= marker.contentIndent || sibling != null) {
                    body += line.drop(minOf(indent, marker.contentIndent))
                } else if (!isBlockStart(line)) {
                    body += line.trim() // lazy continuation
                } else {
                    break
                }
                index++
            }
            var checked: Boolean? = null
            var content: List<String> = body
            TASK.matchEntire(body.first())?.let { task ->
                checked = task.groupValues[1].isNotBlank()
                content = listOf(task.groupValues[2]) + body.drop(1)
            }
            items += ListItem(parseLines(content), checked)
            // Skip blank lines between items of the same list.
            while (index < lines.size && lines[index].isBlank()) {
                val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() }
                val nextMarker = next?.let(::listMarker)
                if (nextMarker != null && nextMarker.ordered == first.ordered && nextMarker.indent in first.indent..first.indent + 1) index++ else break
            }
        }
        out += MarkdownBlock.ListBlock(first.ordered, first.number, items)
        return index
    }

    private fun isBlockStart(line: String): Boolean =
        ATX.matches(line) || FENCE.matches(line) || RULE.matches(line) || line.trimStart().startsWith(">")

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index]
        val delimiter = lines[index + 1]
        if (!header.contains('|') || !TABLE_DELIMITER.matches(delimiter) || !delimiter.contains('-')) return false
        // `a | b` over `---` alone is a setext heading, not a table.
        return delimiter.contains('|') && cells(header).size == cells(delimiter).size
    }

    /** Splits a table row on unescaped pipes outside code spans. */
    fun cells(row: String): List<String> {
        var text = row.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inCode = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] == '|' -> {
                    current.append('|')
                    i++
                }

                c == '`' -> {
                    inCode = !inCode
                    current.append(c)
                }

                c == '|' && !inCode -> {
                    cells += current.toString().trim()
                    current.setLength(0)
                }

                else -> current.append(c)
            }
            i++
        }
        cells += current.toString().trim()
        return cells
    }
}

// ---------------------------------------------------------------------------------- inline

/** Colours the inline renderer needs; resolved once per message. */
data class InlineColors(
    val code: Color,
    val codeBackground: Color,
    val link: Color,
)

private val AUTOLINK = Regex("""https?://[^\s<>()\[\]]+[^\s<>()\[\].,;:!?'"*_~]""")

/**
 * Bold (`**`/`__`), italic (`*`/`_`), bold-italic, `~~strike~~`, `` `code` ``, `[links](url)`,
 * `<autolinks>`, bare URLs and backslash escapes. Unbalanced markers stay literal.
 */
fun buildInlineMarkdown(text: String, colors: InlineColors): AnnotatedString =
    buildAnnotatedString { appendInline(text, colors) }

/** Kept for callers that only care about code spans. */
fun buildInlineMarkdown(text: String, codeColor: Color, codeBackground: Color): AnnotatedString =
    buildInlineMarkdown(text, InlineColors(codeColor, codeBackground, codeColor))

private fun AnnotatedString.Builder.appendInline(text: String, colors: InlineColors) {
    var i = 0
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.setLength(0)
        }
    }
    while (i < text.length) {
        val c = text[i]
        // Escapes
        if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
            plain.append(text[i + 1])
            i += 2
            continue
        }
        // Code span: a run of N backticks closed by the same run.
        if (c == '`') {
            var run = 1
            while (i + run < text.length && text[i + run] == '`') run++
            val fence = "`".repeat(run)
            val end = text.indexOf(fence, i + run)
            if (end > i + run - 1 && end >= 0) {
                flush()
                var code = text.substring(i + run, end)
                if (code.length > 2 && code.startsWith(" ") && code.endsWith(" ")) code = code.substring(1, code.length - 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code, background = colors.codeBackground, fontSize = 0.92.em)) {
                    append(code)
                }
                i = end + run
                continue
            }
            plain.append(fence)
            i += run
            continue
        }
        // Images degrade to a link labelled with their alt text.
        if (c == '!' && text.startsWith("![", i)) {
            val link = parseLink(text, i + 1)
            if (link != null) {
                flush()
                withLink(LinkAnnotation.Url(link.url, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)))) {
                    append("[${link.label.ifBlank { "image" }}]")
                }
                i = link.end
                continue
            }
        }
        if (c == '[') {
            val link = parseLink(text, i)
            if (link != null) {
                flush()
                withLink(LinkAnnotation.Url(link.url, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)))) {
                    appendInline(link.label, colors)
                }
                i = link.end
                continue
            }
        }
        if (c == '<') {
            val close = text.indexOf('>', i + 1)
            val inner = if (close > 0) text.substring(i + 1, close) else ""
            if (inner.startsWith("http://") || inner.startsWith("https://")) {
                flush()
                withLink(LinkAnnotation.Url(inner, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)))) {
                    append(inner)
                }
                i = close + 1
                continue
            }
        }
        if (c == 'h' && (text.startsWith("http://", i) || text.startsWith("https://", i)) && (i == 0 || !text[i - 1].isLetterOrDigit())) {
            val match = AUTOLINK.find(text, i)
            if (match != null && match.range.first == i) {
                flush()
                withLink(LinkAnnotation.Url(match.value, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)))) {
                    append(match.value)
                }
                i = match.range.last + 1
                continue
            }
        }
        if (c == '~' && text.startsWith("~~", i)) {
            val end = closing(text, "~~", i + 2)
            if (end > 0) {
                flush()
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(text.substring(i + 2, end), colors) }
                i = end + 2
                continue
            }
        }
        if (c == '*' || c == '_') {
            val emphasis = emphasisAt(text, i)
            if (emphasis != null) {
                flush()
                val (marker, end) = emphasis
                val style = when (marker.length) {
                    3 -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    2 -> SpanStyle(fontWeight = FontWeight.Bold)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                withStyle(style) { appendInline(text.substring(i + marker.length, end), colors) }
                i = end + marker.length
                continue
            }
        }
        plain.append(c)
        i++
    }
    flush()
}

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"

private data class ParsedLink(val label: String, val url: String, val end: Int)

/** `[label](url "title")` starting at [start] (which points at `[`). */
private fun parseLink(text: String, start: Int): ParsedLink? {
    if (start >= text.length || text[start] != '[') return null
    var depth = 0
    var i = start
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) break
            }
        }
        i++
    }
    if (i >= text.length || i + 1 >= text.length || text[i + 1] != '(') return null
    val close = text.indexOf(')', i + 2)
    if (close < 0) return null
    val target = text.substring(i + 2, close).trim()
    val url = target.substringBefore(' ').removePrefix("<").removeSuffix(">")
    if (url.isBlank() || url.contains('\n')) return null
    return ParsedLink(text.substring(start + 1, i), url, close + 1)
}

/** Index of a closing [marker] after [from] that is not preceded by whitespace. */
private fun closing(text: String, marker: String, from: Int): Int {
    var search = from
    while (true) {
        val end = text.indexOf(marker, search)
        if (end < 0) return -1
        if (end > from && !text[end - 1].isWhitespace()) return end
        search = end + 1
    }
}

/**
 * Opening emphasis run at [i] and the index where its closing marker starts, or null when the
 * run is literal. A closer of the same length wins; otherwise the tail of a longer run that ends
 * a word closes it (`**bold *and italic***`).
 */
private fun emphasisAt(text: String, i: Int): Pair<String, Int>? {
    val c = text[i]
    var run = 0
    while (i + run < text.length && text[i + run] == c) run++
    if (run > 3) return null
    val after = text.getOrNull(i + run) ?: return null
    if (after.isWhitespace()) return null
    // `snake_case_names` are not emphasis.
    if (c == '_' && text.getOrNull(i - 1)?.isLetterOrDigit() == true) return null
    val marker = c.toString().repeat(run)
    var fallback = -1
    var search = i + run
    while (search < text.length) {
        val end = text.indexOf(c, search)
        if (end < 0) break
        var closeRun = 0
        while (end + closeRun < text.length && text[end + closeRun] == c) closeRun++
        val next = text.getOrNull(end + closeRun)
        val closes = end > i + run && !text[end - 1].isWhitespace() &&
            (c != '_' || next?.isLetterOrDigit() != true)
        if (closes && closeRun == run) return marker to end
        if (closes && closeRun > run && fallback < 0 && next?.isLetterOrDigit() != true) fallback = end + closeRun - run
        search = end + closeRun
    }
    return if (fallback >= 0) marker to fallback else null
}

// ---------------------------------------------------------------------------------- rendering

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    monospace: Boolean = false,
) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val colors = InlineColors(
        code = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
        link = MaterialTheme.colorScheme.tertiary,
    )
    val base = if (monospace) style.copy(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * 0.92f) else style
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Blocks(blocks, base, colors, depth = 0)
    }
}

@Composable
private fun Blocks(blocks: List<MarkdownBlock>, style: TextStyle, colors: InlineColors, depth: Int) {
    blocks.forEach { block ->
        when (block) {
            is MarkdownBlock.Heading -> Text(
                text = buildInlineMarkdown(block.text, colors),
                style = headingStyle(block.level, style),
                modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
            )

            is MarkdownBlock.Paragraph -> Text(text = buildInlineMarkdown(block.text, colors), style = style)

            is MarkdownBlock.Code -> CodeBlockView(block)

            is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Blocks(block.blocks, style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), colors, depth)
                }
            }

            is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row {
                        val marker = when {
                            item.checked == true -> "☑"
                            item.checked == false -> "☐"
                            block.ordered -> "${block.start + index}."
                            else -> BULLETS[depth % BULLETS.size]
                        }
                        Text(
                            text = marker,
                            style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.widthIn(min = if (block.ordered) 24.dp else 16.dp).padding(end = 6.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val itemStyle = if (item.checked == true) {
                                style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
                            } else {
                                style
                            }
                            Blocks(item.blocks, itemStyle, colors, depth + 1)
                        }
                    }
                }
            }

            is MarkdownBlock.Table -> TableView(block, style, colors)

            MarkdownBlock.Rule -> HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

private val BULLETS = listOf("•", "◦", "▪")

@Composable
private fun headingStyle(level: Int, base: TextStyle): TextStyle {
    val typography = MaterialTheme.typography
    val scale = base.fontSize.value / typography.bodyLarge.fontSize.value.coerceAtLeast(1f)
    val target = when (level) {
        1 -> typography.titleLarge
        2 -> typography.titleMedium.copy(fontSize = 18.sp)
        3 -> typography.titleSmall.copy(fontSize = 16.sp)
        else -> typography.bodyLarge
    }
    return base.copy(
        fontSize = target.fontSize * scale,
        lineHeight = target.lineHeight * scale,
        fontWeight = if (level <= 3) FontWeight.Bold else FontWeight.SemiBold,
        color = if (level >= 5) MaterialTheme.colorScheme.onSurfaceVariant else base.color,
    )
}

@Composable
private fun TableView(table: MarkdownBlock.Table, style: TextStyle, colors: InlineColors) {
    // Column widths from the longest cell, capped so one long cell does not push the rest away.
    val widths = remember(table) {
        table.header.indices.map { column ->
            val longest = (listOf(table.header[column]) + table.rows.map { it[column] }).maxOf { it.length }
            (longest * 7.2f + 20f).coerceIn(56f, 280f)
        }
    }
    val border = MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            TableRow(table.header, widths, table.align, style.copy(fontWeight = FontWeight.SemiBold), colors, MaterialTheme.colorScheme.surfaceContainerHigh)
            table.rows.forEach { row ->
                Box(Modifier.width(widths.sum().dp).height(1.dp).background(border))
                TableRow(row, widths, table.align, style, colors, null)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    widths: List<Float>,
    align: List<MarkdownBlock.Align>,
    style: TextStyle,
    colors: InlineColors,
    background: Color?,
) {
    Row(if (background != null) Modifier.background(background) else Modifier) {
        cells.forEachIndexed { column, cell ->
            Text(
                text = buildInlineMarkdown(cell, colors),
                style = style.copy(
                    fontSize = style.fontSize * 0.93f,
                    textAlign = when (align.getOrNull(column)) {
                        MarkdownBlock.Align.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                        MarkdownBlock.Align.END -> androidx.compose.ui.text.style.TextAlign.End
                        else -> androidx.compose.ui.text.style.TextAlign.Start
                    },
                ),
                modifier = Modifier.width(widths[column].dp).padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.Code) {
    val context = LocalContext.current
    val copied = stringResource(R.string.copied_to_clipboard)
    val highlighted = rememberHighlighted(block)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { copy(context, block.code, copied) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_action_copy),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Text(
                    text = highlighted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun copy(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("code", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun rememberHighlighted(block: MarkdownBlock.Code): AnnotatedString {
    val palette = SyntaxPalette(
        keyword = MaterialTheme.colorScheme.primary,
        string = Color(0xFF7FB069).let { if (ai.opencode.android.ui.theme.LocalDarkTheme.current) it else Color(0xFF2E7D32) },
        comment = MaterialTheme.colorScheme.onSurfaceVariant,
        number = MaterialTheme.colorScheme.tertiary,
    )
    return remember(block, palette) { SyntaxHighlighter.highlight(block.code, block.language, palette) }
}

data class SyntaxPalette(val keyword: Color, val string: Color, val comment: Color, val number: Color)

/**
 * Token-level highlighting good enough for chat: comments, strings, numbers and the keywords of
 * the common languages. No grammar, so it never fails on partial code.
 */
object SyntaxHighlighter {

    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "when", "if", "else", "for",
        "while", "do", "return", "import", "package", "private", "public", "protected", "internal", "override",
        "suspend", "const", "null", "true", "false", "this", "super", "new", "try", "catch", "finally", "throw",
        "function", "let", "async", "await", "export", "default", "from", "extends", "implements", "static",
        "def", "self", "None", "True", "False", "in", "is", "not", "and", "or", "lambda", "with", "as", "pass",
        "fn", "pub", "mut", "impl", "struct", "trait", "use", "mod", "match", "loop", "go", "func", "type",
        "switch", "case", "break", "continue", "void", "int", "long", "string", "bool", "boolean", "char",
        "echo", "then", "fi", "esac", "done", "local", "readonly", "elif", "yield", "typeof", "instanceof",
    )

    private val HASH_COMMENT_LANGS = setOf("sh", "bash", "zsh", "shell", "python", "py", "ruby", "rb", "yaml", "yml", "toml", "dockerfile", "makefile", "r", "perl", "conf", "ini")

    fun highlight(code: String, language: String?, palette: SyntaxPalette): AnnotatedString {
        val lang = language?.lowercase().orEmpty()
        if (lang in setOf("text", "txt", "plain", "diff", "patch", "log", "output")) return AnnotatedString(code)
        val hashComments = lang in HASH_COMMENT_LANGS || lang.isEmpty() && code.lineSequence().any { it.trimStart().startsWith("#!") }
        return buildAnnotatedString {
            var i = 0
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '/' && code.startsWith("//", i) && !hashComments -> {
                        val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                        withStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)) { append(code.substring(i, end)) }
                        i = end
                    }

                    c == '/' && code.startsWith("/*", i) -> {
                        val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                        withStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)) { append(code.substring(i, end)) }
                        i = end
                    }

                    c == '#' && hashComments -> {
                        val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                        withStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)) { append(code.substring(i, end)) }
                        i = end
                    }

                    c == '"' || c == '\'' || c == '`' -> {
                        var end = i + 1
                        while (end < code.length && code[end] != c && code[end] != '\n') {
                            if (code[end] == '\\') end++
                            end++
                        }
                        end = (end + 1).coerceAtMost(code.length)
                        withStyle(SpanStyle(color = palette.string)) { append(code.substring(i, end)) }
                        i = end
                    }

                    c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                        var end = i
                        while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '.' || code[end] == '_')) end++
                        withStyle(SpanStyle(color = palette.number)) { append(code.substring(i, end)) }
                        i = end
                    }

                    c.isLetter() || c == '_' -> {
                        var end = i
                        while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                        val word = code.substring(i, end)
                        if (word in KEYWORDS) {
                            withStyle(SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold)) { append(word) }
                        } else {
                            append(word)
                        }
                        i = end
                    }

                    else -> {
                        append(c)
                        i++
                    }
                }
            }
        }
    }
}
