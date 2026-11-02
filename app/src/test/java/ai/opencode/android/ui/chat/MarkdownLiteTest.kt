package ai.opencode.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {

    @Test
    fun `plain text becomes one paragraph`() {
        val blocks = MarkdownLite.parse("Hello there")

        assertEquals(1, blocks.size)
        assertEquals("Hello there", (blocks.single() as MarkdownBlock.Paragraph).text)
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
    fun `unterminated fence is still rendered`() {
        val blocks = MarkdownLite.parse("text\n```\ncode only")

        assertTrue(blocks.any { it is MarkdownBlock.Code })
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(MarkdownLite.parse("").isEmpty())
    }

    @Test
    fun `multiple paragraphs keep their line breaks`() {
        val blocks = MarkdownLite.parse("line one\nline two\n\nline three")
        assertEquals(1, blocks.size)
        assertEquals("line one\nline two\n\nline three", (blocks.single() as MarkdownBlock.Paragraph).text)
    }
}
