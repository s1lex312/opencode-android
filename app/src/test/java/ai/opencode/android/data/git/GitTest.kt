package ai.opencode.android.data.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitTest {

    @Test
    fun `status with branch, tracking and every kind of entry`() {
        val status = Git.parseStatus(
            """
            ## main...origin/main [ahead 2, behind 1]
            M  staged.kt
             M worktree.kt
            MM both.kt
            A  added.kt
            ?? new file.txt
            R  old.kt -> renamed.kt
            UU conflict.kt
            "?? "weird name.txt"
            """.trimIndent().replace("\"?? \"weird name.txt\"", "?? \"we\\\"ird \\303\\251.txt\""),
        )
        assertTrue(status.isRepo)
        assertEquals("main", status.branch)
        assertEquals("origin/main", status.upstream)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
        assertEquals(listOf("staged.kt", "both.kt", "added.kt", "renamed.kt"), status.staged.map { it.path })
        assertEquals(listOf("worktree.kt", "both.kt", "new file.txt", "conflict.kt", "we\"ird é.txt"), status.unstaged.map { it.path })
        assertEquals("old.kt", status.files.first { it.path == "renamed.kt" }.origPath)
        assertTrue(status.files.first { it.path == "both.kt" }.hunksOn(true))
        assertTrue(status.files.first { it.path == "both.kt" }.hunksOn(false))
        assertFalse(status.files.first { it.path == "added.kt" }.hunksOn(true))
        assertFalse(status.files.first { it.path == "conflict.kt" }.hunksOn(false))
    }

    @Test
    fun `not a repository and fresh repositories`() {
        assertFalse(Git.parseStatus(Git.NOT_A_REPO).isRepo)
        val fresh = Git.parseStatus("## No commits yet on main\n?? a.txt")
        assertEquals("main", fresh.branch)
        assertNull(fresh.upstream)
        assertEquals(1, fresh.files.size)
        assertEquals("HEAD", Git.parseStatus("## HEAD (no branch)").branch)
    }

    @Test
    fun `octal and escaped quoting`() {
        assertEquals("a\tb", Git.unquote("\"a\\tb\""))
        assertEquals("ж", Git.unquote("\"\\320\\266\""))
        assertEquals("plain", Git.unquote("plain"))
    }

    private val diff = """
        diff --git a/src/App.kt b/src/App.kt
        index 1111111..2222222 100644
        --- a/src/App.kt
        +++ b/src/App.kt
        @@ -1,3 +1,3 @@
         a
        -b
        +B
         c
        @@ -10,2 +10,3 @@ fun x()
         y
        +z
         w
    """.trimIndent()

    @Test
    fun `diff splits into file header and raw hunks`() {
        val files = Git.splitDiff(diff)
        assertEquals(1, files.size)
        val file = files.single()
        assertEquals("src/App.kt", file.path)
        assertEquals(4, file.headerLines.size)
        assertEquals(2, file.hunks.size)
        assertEquals(listOf(" a", "-b", "+B", " c"), file.hunks[0].lines)
        assertEquals("@@ -10,2 +10,3 @@ fun x()", file.hunks[1].header)
    }

    @Test
    fun `a single-hunk patch keeps the header`() {
        val file = Git.splitDiff(diff).single()
        val patch = file.patchFor(file.hunks[1])
        assertEquals(
            "diff --git a/src/App.kt b/src/App.kt\nindex 1111111..2222222 100644\n--- a/src/App.kt\n+++ b/src/App.kt\n" +
                "@@ -10,2 +10,3 @@ fun x()\n y\n+z\n w\n",
            patch,
        )
    }

    @Test
    fun `new files and binaries`() {
        val files = Git.splitDiff(
            "diff --git a/n.txt b/n.txt\nnew file mode 100644\n--- /dev/null\n+++ b/n.txt\n@@ -0,0 +1 @@\n+hi\n" +
                "diff --git a/i.png b/i.png\nBinary files a/i.png and b/i.png differ",
        )
        assertEquals(listOf("n.txt", "i.png"), files.map { it.path })
        assertEquals(listOf("+hi"), files[0].hunks.single().lines)
        assertTrue(files[1].binary)
    }

    @Test
    fun `log lines`() {
        val log = Git.parseLog("abc1234\tAda\t2 hours ago\tfix: thing\tmore\nbroken")
        assertEquals(listOf(GitCommit("abc1234", "Ada", "2 hours ago", "fix: thing\tmore")), log)
    }

    @Test
    fun `scripts quote paths and carry patches as base64`() {
        val file = GitFile("it's.kt", ' ', 'M')
        assertTrue(Git.diffScript(file, staged = false).endsWith("-- 'it'\\''s.kt'"))
        assertTrue(Git.stageScript(listOf("a b")).endsWith("-- 'a b'"))
        val apply = Git.applyScript("patch\n", cached = true, reverse = true)
        val encoded = java.util.Base64.getEncoder().encodeToString("patch\n".toByteArray())
        assertTrue(apply.contains("'$encoded' | base64 -d | git apply --recount --whitespace=nowarn --cached -R -"))
        val commit = Git.commitScript("msg 'quoted'", amend = true, stageAll = true)
        assertTrue(commit.startsWith("git add -A && printf '%s' "))
        assertTrue(commit.endsWith("git commit -F - --amend"))
        assertEquals("rm -rf -- 'n.txt'", Git.discardScript(GitFile("n.txt", '?', '?')))
    }
}
