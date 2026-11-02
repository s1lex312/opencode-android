package ai.opencode.android.data.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffParserTest {

    @Test
    fun `parses a single git-style file with correct counters and line numbers`() {
        val patch = """
            diff --git a/src/app.kt b/src/app.kt
            index 1111111..2222222 100644
            --- a/src/app.kt
            +++ b/src/app.kt
            @@ -1,3 +1,4 @@
             fun main() {
            -    println("hi")
            +    println("hello")
            +    println("world")
             }
        """.trimIndent()

        val files = UnifiedDiffParser.parse(patch)

        assertEquals(1, files.size)
        val file = files.single()
        assertEquals("src/app.kt", file.path)
        assertEquals(2, file.additions)
        assertEquals(1, file.deletions)
        assertEquals(1, file.hunks.size)

        val hunk = file.hunks.single()
        assertEquals(1, hunk.oldStart)
        assertEquals(3, hunk.oldCount)
        assertEquals(1, hunk.newStart)
        assertEquals(4, hunk.newCount)
        assertEquals(5, hunk.lines.size)

        val removed = hunk.lines[1]
        assertEquals(DiffLineKind.REMOVED, removed.kind)
        assertEquals(2, removed.oldLineNumber)
        assertNull(removed.newLineNumber)

        val firstAdded = hunk.lines[2]
        assertEquals(DiffLineKind.ADDED, firstAdded.kind)
        assertEquals(2, firstAdded.newLineNumber)

        val lastContext = hunk.lines.last()
        assertEquals(DiffLineKind.CONTEXT, lastContext.kind)
        assertEquals(3, lastContext.oldLineNumber)
        assertEquals(4, lastContext.newLineNumber)
    }

    @Test
    fun `splits multiple files in one patch`() {
        val patch = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1,1 +1,1 @@
            -old
            +new
            diff --git a/b.txt b/b.txt
            --- /dev/null
            +++ b/b.txt
            @@ -0,0 +1,2 @@
            +one
            +two
        """.trimIndent()

        val files = UnifiedDiffParser.parse(patch)

        assertEquals(2, files.size)
        assertEquals("a.txt", files[0].path)
        assertEquals(1, files[0].additions)
        assertEquals(1, files[0].deletions)
        assertEquals("b.txt", files[1].path)
        assertNull(files[1].oldPath)
        assertEquals(2, files[1].additions)
        assertEquals(0, files[1].deletions)
    }

    @Test
    fun `keeps no-newline markers as notes`() {
        val patch = """
            --- a/notes.md
            +++ b/notes.md
            @@ -1 +1 @@
            -done
            \ No newline at end of file
            +done!
            \ No newline at end of file
        """.trimIndent()

        val files = UnifiedDiffParser.parse(patch)
        val kinds = files.single().hunks.single().lines.map { it.kind }

        assertTrue(kinds.contains(DiffLineKind.NOTE))
        assertEquals(1, files.single().additions)
        assertEquals(1, files.single().deletions)
    }

    @Test
    fun `handles CRLF input`() {
        val patch = "--- a/x\r\n+++ b/x\r\n@@ -1 +1 @@\r\n-old\r\n+new\r\n"

        val file = UnifiedDiffParser.parse(patch).single()

        assertEquals("x", file.path)
        assertEquals("old", file.hunks.single().lines[0].text)
        assertEquals("new", file.hunks.single().lines[1].text)
    }

    @Test
    fun `detects binary patches`() {
        val patch = "Binary files a/logo.png and b/logo.png differ"

        val file = UnifiedDiffParser.parse(patch).single()

        assertTrue(file.isBinary)
        assertNull(file.path.takeIf { it != "unknown" })
    }

    @Test
    fun `never throws on garbage input`() {
        val files = UnifiedDiffParser.parse("this is not a diff\n@@ broken @@\n+orphan")

        assertTrue(files.isNotEmpty())
        assertEquals(1, files.first().additions)
    }

    @Test
    fun `empty patch yields nothing`() {
        assertTrue(UnifiedDiffParser.parse("").isEmpty())
    }
}
