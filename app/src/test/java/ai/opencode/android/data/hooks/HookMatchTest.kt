package ai.opencode.android.data.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookMatchTest {

    @Test
    fun `file globs`() {
        val dir = "/root/app"
        assertTrue(HookMatch.file("", "/root/app/a.kt", dir))
        assertTrue(HookMatch.file("*.kt", "/root/app/src/deep/A.kt", dir))
        assertFalse(HookMatch.file("*.kt", "/root/app/src/A.java", dir))
        assertTrue(HookMatch.file("*.{js,ts}", "/root/app/web/x.ts", dir))
        assertFalse(HookMatch.file("*.{js,ts}", "/root/app/web/x.tsx", dir))
        assertTrue(HookMatch.file("src/**/*.ts", "/root/app/src/a/b/c.ts", dir))
        assertTrue(HookMatch.file("src/**/*.ts", "/root/app/src/c.ts", dir))
        assertFalse(HookMatch.file("src/**/*.ts", "/root/app/test/c.ts", dir))
        assertTrue(HookMatch.file("*.py, *.pyi", "/root/app/m.pyi", dir))
        assertTrue(HookMatch.file("Makefile", "/root/app/Makefile", dir))
    }

    @Test
    fun `tool regex`() {
        assertTrue(HookMatch.tool("", "bash"))
        assertTrue(HookMatch.tool("edit|bash", "bash"))
        assertTrue(HookMatch.tool("^BASH$", "bash"))
        assertFalse(HookMatch.tool("edit", "webfetch"))
        // An invalid regex falls back to plain comparison.
        assertTrue(HookMatch.tool("(", "("))
    }

    @Test
    fun `placeholders become quoted values`() {
        assertEquals("prettier --write '/a b/it'\\''s.ts'", HookMatch.substitute("prettier --write {file}", mapOf("FILE" to "/a b/it's.ts")))
        assertEquals("echo \$FILE", HookMatch.substitute("echo \$FILE", mapOf("FILE" to "x")))
    }

    @Test
    fun `edited files from tool inputs`() {
        assertEquals(listOf("/r/a.kt"), HookRunner.editedFiles("edit", """{"filePath":"/r/a.kt","oldString":"a","newString":"b"}"""))
        assertEquals(listOf("/r/b.kt"), HookRunner.editedFiles("write", """{"filePath":"/r/b.kt","content":"x"}"""))
        assertEquals(emptyList<String>(), HookRunner.editedFiles("read", """{"filePath":"/r/a.kt"}"""))
        assertEquals(
            listOf("src/x.ts", "src/y.ts"),
            HookRunner.editedFiles("patch", """{"patchText":"*** Begin Patch\n*** Update File: src/x.ts\n@@\n-a\n+b\n*** Add File: src/y.ts\n+c\n*** End Patch"}"""),
        )
        // v2 delivers the input without the tool name.
        assertEquals(listOf("/r/c.kt"), HookRunner.editedFiles(null, """{"filePath":"/r/c.kt","content":"x"}"""))
        assertEquals(emptyList<String>(), HookRunner.editedFiles(null, """{"filePath":"/r/c.kt"}"""))
        // Large inputs reach us truncated, no longer valid JSON.
        assertEquals(listOf("/r/big.kt"), HookRunner.editedFiles("write", "{\n  \"filePath\": \"/r/big.kt\",\n  \"content\": \"aaaa\n… (truncated)"))
    }
}
