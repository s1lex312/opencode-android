package ai.opencode.android.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {

    private val colors = InlineColors(Color.Red, Color.Gray, Color.Blue)

    @Test
    fun `plain text becomes one paragraph`() {
        val blocks = MarkdownLite.parse("Hello there")
        assertEquals(listOf(MarkdownBlock.Paragraph("Hello there")), blocks)
    }

    @Test
    fun `fenced code blocks are separated from prose`() {
        val markdown = """
            Run this:
            ```bash
            ls -la
            echo hi
            ```
            Done.
        """.trimIndent()

        val blocks = MarkdownLite.parse(markdown)

        assertEquals(3, blocks.size)
        assertEquals("Run this:", (blocks[0] as MarkdownBlock.Paragraph).text)
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("bash", code.language)
        assertEquals("ls -la\necho hi", code.code)
        assertEquals("Done.", (blocks[2] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `tilde fences and unterminated fences`() {
        assertEquals(MarkdownBlock.Code("py", "x = 1"), MarkdownLite.parse("~~~py\nx = 1\n~~~").single())
        assertTrue(MarkdownLite.parse("text\n```\ncode only").any { it is MarkdownBlock.Code })
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(MarkdownLite.parse("").isEmpty())
        assertTrue(MarkdownLite.parse("  \n ").isEmpty())
    }

    @Test
    fun `blank lines split paragraphs, single newlines stay`() {
        val blocks = MarkdownLite.parse("line one\nline two\n\nline three")
        assertEquals(
            listOf(MarkdownBlock.Paragraph("line one\nline two"), MarkdownBlock.Paragraph("line three")),
            blocks,
        )
    }

    @Test
    fun `atx headings of every level`() {
        val blocks = MarkdownLite.parse("# One\n## Two\n### Three ###\n#### Four\n##### Five\n###### Six\n####### seven")
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6),
            blocks.filterIsInstance<MarkdownBlock.Heading>().map { it.level },
        )
        assertEquals("Three", (blocks[2] as MarkdownBlock.Heading).text)
        // Seven hashes is not a heading.
        assertEquals(MarkdownBlock.Paragraph("####### seven"), blocks.last())
        // No space after the hashes is not a heading either.
        assertEquals(MarkdownBlock.Paragraph("#hashtag"), MarkdownLite.parse("#hashtag").single())
    }

    @Test
    fun `heading directly followed by text`() {
        val blocks = MarkdownLite.parse("### Summary\nAll tests pass.")
        assertEquals(MarkdownBlock.Heading(3, "Summary"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("All tests pass."), blocks[1])
    }

    @Test
    fun `setext headings and thematic breaks`() {
        val blocks = MarkdownLite.parse("Title\n=====\n\ntext\n\n---\n\nSub\n---")
        assertEquals(MarkdownBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("text"), blocks[1])
        assertEquals(MarkdownBlock.Rule, blocks[2])
        assertEquals(MarkdownBlock.Heading(2, "Sub"), blocks[3])
        assertEquals(MarkdownBlock.Rule, MarkdownLite.parse("***").single())
    }

    @Test
    fun `bullet lists with nesting and tasks`() {
        val markdown = """
            - first
            - second
              - nested a
              - nested b
            - [x] done
            - [ ] todo
        """.trimIndent()
        val list = MarkdownLite.parse(markdown).single() as MarkdownBlock.ListBlock
        assertEquals(false, list.ordered)
        assertEquals(4, list.items.size)
        assertEquals(MarkdownBlock.Paragraph("first"), list.items[0].blocks.single())
        val nested = list.items[1].blocks[1] as MarkdownBlock.ListBlock
        assertEquals(2, nested.items.size)
        assertEquals(true, list.items[2].checked)
        assertEquals(false, list.items[3].checked)
        assertNull(list.items[0].checked)
    }

    @Test
    fun `ordered lists keep their start number`() {
        val list = MarkdownLite.parse("3. three\n4. four").single() as MarkdownBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `list after a paragraph line`() {
        val blocks = MarkdownLite.parse("Changes:\n- a\n- b\n\nDone")
        assertEquals(MarkdownBlock.Paragraph("Changes:"), blocks[0])
        assertEquals(2, (blocks[1] as MarkdownBlock.ListBlock).items.size)
        assertEquals(MarkdownBlock.Paragraph("Done"), blocks[2])
    }

    @Test
    fun `loose list items separated by blank lines stay one list`() {
        val list = MarkdownLite.parse("1. a\n\n2. b\n\n3. c").single() as MarkdownBlock.ListBlock
        assertEquals(3, list.items.size)
    }

    @Test
    fun `block quotes nest blocks`() {
        val quote = MarkdownLite.parse("> ### Note\n> be careful").single() as MarkdownBlock.Quote
        assertEquals(MarkdownBlock.Heading(3, "Note"), quote.blocks[0])
        assertEquals(MarkdownBlock.Paragraph("be careful"), quote.blocks[1])
    }

    @Test
    fun `gfm tables with alignment and escaped pipes`() {
        val markdown = """
            | File | Lines | Note |
            |:-----|------:|:----:|
            | a.kt | 10 | `x | y` |
            | b.kt | 2 | a \| b |
        """.trimIndent()
        val table = MarkdownLite.parse(markdown).single() as MarkdownBlock.Table
        assertEquals(listOf("File", "Lines", "Note"), table.header)
        assertEquals(listOf(MarkdownBlock.Align.START, MarkdownBlock.Align.END, MarkdownBlock.Align.CENTER), table.align)
        assertEquals(listOf("a.kt", "10", "`x | y`"), table.rows[0])
        assertEquals("a | b", table.rows[1][2])
    }

    @Test
    fun `inline emphasis, strike, code and links`() {
        val text = buildInlineMarkdown("a **bold** *it* ***both*** ~~gone~~ `code` [site](https://x.dev) end", colors)
        assertEquals("a bold it both gone code site end", text.text)
        val styles = text.spanStyles
        assertTrue(styles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "bold" })
        assertTrue(styles.any { it.item.fontStyle == FontStyle.Italic && text.text.substring(it.start, it.end) == "it" })
        assertTrue(styles.any { it.item.textDecoration == TextDecoration.LineThrough })
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://x.dev", (link.item as LinkAnnotation.Url).url)
        assertEquals("site", text.text.substring(link.start, link.end))
    }

    @Test
    fun `snake case, escapes and lone markers stay literal`() {
        assertEquals("my_var_name 2 * 3 *x", buildInlineMarkdown("my_var_name 2 * 3 *x", colors).text)
        assertEquals("*not italic*", buildInlineMarkdown("\\*not italic\\*", colors).text)
        assertTrue(buildInlineMarkdown("my_var_name", colors).spanStyles.isEmpty())
    }

    @Test
    fun `bare urls become links without trailing punctuation`() {
        val text = buildInlineMarkdown("see https://opencode.ai/docs.", colors)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://opencode.ai/docs", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun `nested emphasis inside bold`() {
        val text = buildInlineMarkdown("**use `gradle` *now***", colors)
        assertEquals("use gradle now", text.text)
    }

    @Test
    fun `syntax highlighter keeps the text intact`() {
        val code = "fun main() { // hi\n  val s = \"x\" + 1\n}"
        val palette = SyntaxPalette(Color.Red, Color.Green, Color.Gray, Color.Blue)
        val highlighted = SyntaxHighlighter.highlight(code, "kotlin", palette)
        assertEquals(code, highlighted.text)
        assertTrue(highlighted.spanStyles.any { it.item.color == Color.Red })
        assertTrue(highlighted.spanStyles.any { it.item.color == Color.Gray })
    }
}
