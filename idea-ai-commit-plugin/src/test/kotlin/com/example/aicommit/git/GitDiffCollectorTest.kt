package com.example.aicommit.git

import com.example.aicommit.model.ChangeStatus
import com.intellij.diff.util.Side
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vcs.ex.PartialLocalLineStatusTracker
import java.lang.reflect.Proxy
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GitDiffCollectorTest {
    private val collector = GitDiffCollector()
    private fun repo(block: (File) -> Unit) {
        val root = Files.createTempDirectory("diff-test").toFile()
        try {
            git(root, "init", "--quiet")
            git(root, "config", "user.email", "test@example.invalid")
            git(root, "config", "user.name", "Test")
            block(root)
        } finally { root.deleteRecursively() }
    }
    private fun git(root: File, vararg args: String) = collector.execAndRead(listOf("git") + args, root)
    private fun commit(root: File) {
        git(root, "add", ".")
        git(root, "commit", "--quiet", "-m", "base")
    }

    @Test
    fun `initial repository staged and untracked files have real hunks`() = repo { root ->
        val staged = File(root, "staged.txt").apply { writeText("first\n") }
        val fresh = File(root, "new.txt").apply { writeText("  whitespace  \nlast") }
        git(root, "add", "staged.txt")
        val inputs = collector.collectPaths(listOf(GitPathChange(null, staged.path)), listOf(fresh.path))
        val diff = StructuredDiffBuilder().buildFromPatch(inputs, 10, 10)
        assertEquals(2, diff.files.size)
        assertEquals(3, diff.totalAdditions)
        assertTrue(diff.keyHunks.any { it.patch.contains("+  whitespace  \n+last\n\\ No newline at end of file") })
        assertFalse(File(root, ".git/refs/heads/master").exists())
    }

    @Test
    fun `each selected path resolves its own repository including deleted paths`() = repo { one -> repo { two ->
        val a = File(one, "same.txt").apply { writeText("old-one\n") }
        val b = File(two, "same.txt").apply { writeText("old-two\n") }
        commit(one); commit(two)
        a.writeText("new-one\n"); b.delete()
        val diff = StructuredDiffBuilder().buildFromPatch(collector.collectPaths(
            listOf(GitPathChange(a.path, a.path), GitPathChange(b.path, null)), emptyList()), 10, 10)
        assertEquals(setOf(one.canonicalPath, two.canonicalPath), diff.files.map { it.repositoryRoot }.toSet())
        assertEquals(setOf(ChangeStatus.MODIFIED, ChangeStatus.DELETED), diff.files.map { it.status }.toSet())
        assertEquals(1, diff.totalAdditions)
        assertEquals(2, diff.totalDeletions)
    } }

    @Test
    fun `rename retains before and after paths and git rename metadata`() = repo { root ->
        val old = File(root, "old name.txt").apply { writeText("unchanged\n") }
        commit(root)
        git(root, "mv", "old name.txt", "new name.txt")
        val input = collector.collectPaths(listOf(GitPathChange(old.path, File(root, "new name.txt").path)), emptyList()).single()
        assertEquals(ChangeStatus.RENAMED, input.status)
        assertEquals("old name.txt", input.oldPath)
        assertEquals("new name.txt", input.relativePath)
        assertTrue(input.patch.contains("rename from old name.txt"))
    }

    @Test
    fun `rename before initial commit is an addition relative to empty tree`() = repo { root ->
        val old = File(root, "old.txt").apply { writeText("initial\n") }
        git(root, "add", "old.txt")
        git(root, "mv", "old.txt", "new.txt")
        val input = collector.collectPaths(listOf(GitPathChange(old.path, File(root, "new.txt").path)), emptyList()).single()
        assertEquals(ChangeStatus.ADDED, input.status)
        assertNull(input.oldPath)
        assertTrue(input.patch.contains("new file mode"))
        assertTrue(input.patch.contains("+initial"))
        assertFalse(input.patch.contains("rename from"))
    }

    @Test
    fun `process output preserves whitespace and errors include stderr`() {
        assertEquals("  hello  \n\n", collector.execAndRead(listOf("printf", "  hello  \\n\\n"), File("/tmp")))
        assertEquals("out", collector.execAndRead(listOf("sh", "-c", "printf out; printf err >&2"), File("/tmp")))
        val error = assertFailsWith<GitCollectionException> {
            collector.execAndRead(listOf("git", "not-a-real-command"), File("/tmp"))
        }
        assertTrue(error.message.orEmpty().contains("not-a-real-command"))
        assertFailsWith<GitCollectionException> { collector.execAndRead(listOf("nonexistent_cmd_xyz"), File("/tmp")) }
    }

    @Test
    fun `empty non git and unchanged selections are rejected`() {
        assertFailsWith<IllegalArgumentException> { collector.collectPaths(emptyList(), emptyList()) }
        val file = Files.createTempFile("non-git", ".txt").toFile()
        try { assertFailsWith<GitCollectionException> { collector.collectPaths(emptyList(), listOf(file.path)) } }
        finally { file.delete() }
        repo { root ->
            val unchanged = File(root, "file.txt").apply { writeText("same\n") }; commit(root)
            assertFailsWith<GitCollectionException> {
                collector.collectPaths(listOf(GitPathChange(unchanged.path, unchanged.path)), emptyList())
            }
            assertFailsWith<GitCollectionException> { collector.collectPaths(emptyList(), listOf(unchanged.path)) }
        }
    }

    @Test
    fun `timeout kills process promptly`() {
        val start = System.nanoTime()
        assertFailsWith<GitCollectionException> {
            GitDiffCollector(DiffLimits(processTimeoutMillis = 150)).execAndRead(listOf("sleep", "10"), File("/tmp"))
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test
    fun `sensitive binary and oversized files are skipped before reading`() = repo { root ->
        val secret = File(root, ".env").apply { writeText("DO_NOT_READ") }
        val binary = File(root, "data.dat").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        val big = File(root, "big.txt").apply { writeText("x".repeat(21_000)) }
        val inputs = collector.collectPaths(emptyList(), listOf(secret.path, binary.path, big.path))
        assertEquals(listOf("sensitive", "binary", "size"), inputs.map { it.skipReason })
        assertTrue(inputs.all { it.patch.isEmpty() })
    }

    @Test
    fun `directory symlink outside Git is skipped without aborting other selected changes`() = repo { root ->
        val tracked = File(root, ".gitignore").apply { writeText("old\n") }
        commit(root)
        tracked.writeText("new\n")
        val outside = Files.createTempDirectory("external-node-package")
        val link = root.toPath().resolve("node_modules/typebox")
        Files.createDirectories(link.parent)
        try {
            Files.createSymbolicLink(link, outside)
            val inputs = collector.collectPaths(listOf(GitPathChange(tracked.path, tracked.path)), listOf(link.toString()))
            assertEquals("symbolicLink", inputs.single { it.relativePath == "node_modules/typebox" }.skipReason)
            val diff = StructuredDiffBuilder().buildFromPatch(inputs, 12, 3)
            assertEquals(2, diff.totalFiles)
            assertEquals(".gitignore", diff.files.single().relativePath)
            assertTrue(diff.keyHunks.single().patch.contains("+new"))
            assertEquals("symbolicLink", diff.omissions.single().reason)
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `cancellation propagates unchanged and kills command and child`() {
        val pidFile = Files.createTempFile("cancel-diff", ".pid").toFile().apply { delete() }
        val canceled = ProcessCanceledException()
        val canceling = GitDiffCollector(DiffLimits()) {
            if (pidFile.exists() && pidFile.readText().trim().isNotEmpty()) throw canceled
        }
        try {
            val thrown = assertFailsWith<ProcessCanceledException> {
                canceling.execAndRead(listOf("sh", "-c", "sleep 10 & child=$!; printf '%s %s' $$ \"\$child\" > \"$1\"; wait", "sh", pidFile.path), File("/tmp"))
            }
            assertSame(canceled, thrown)
            pidFile.readText().trim().split(' ').map { it.toLong() }.forEach { pid ->
                val process = ProcessHandle.of(pid)
                if (process.isPresent) process.get().onExit().get(2, java.util.concurrent.TimeUnit.SECONDS)
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            }
        } finally { pidFile.delete() }
    }

    @Test
    fun `SDK tracker snapshot uses selected lists and honors excluded ranges`() {
        val calls = mutableListOf<List<Any?>>()
        val tracker = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PartialLocalLineStatusTracker::class.java)) { _, method, args ->
            when (method.name) {
                "getAffectedChangeListsIds" -> listOf("one", "two")
                "getChangesToBeCommitted" -> { calls.add(args.toList()); "selected\n" }
                else -> error("Unexpected tracker method: ${method.name}")
            }
        } as PartialLocalLineStatusTracker
        assertFailsWith<GitCollectionException> { collector.selectedContent(tracker, null) }
        assertEquals("selected\n", collector.selectedContent(tracker, listOf("one", "two")))
        assertEquals(listOf(Side.LEFT, listOf("one", "two"), true), calls.single())
        val invalid = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PartialLocalLineStatusTracker::class.java)) { _, _, _ -> null }
            as PartialLocalLineStatusTracker
        assertFailsWith<GitCollectionException> { collector.selectedContent(invalid, listOf("one")) }
    }

    @Test
    fun `unstaged rename includes new content and literal pathspec does not select neighbors`() = repo { root ->
        val old = File(root, "old.txt").apply { writeText("old\n") }
        val special = File(root, ":(glob)*.txt").apply { writeText("special\n") }
        val neighbor = File(root, "neighbor.txt").apply { writeText("neighbor\n") }
        commit(root)
        val renamed = File(root, "new.txt")
        old.renameTo(renamed); renamed.writeText("new\n")
        special.writeText("literal\n"); neighbor.writeText("DO_NOT_INCLUDE\n")
        val inputs = collector.collectPaths(listOf(GitPathChange(old.path, renamed.path), GitPathChange(special.path, special.path)), emptyList())
        assertTrue(inputs.first().patch.contains("+new"))
        assertEquals(ChangeStatus.RENAMED, inputs.first().status)
        assertTrue(inputs.last().patch.contains("+literal"))
        assertFalse(inputs.last().patch.contains("DO_NOT_INCLUDE"))
    }

    @Test
    fun `empty new files have summaries with no invented lines`() = repo { root ->
        val empty = File(root, "empty.txt").apply { writeText("") }
        val diff = StructuredDiffBuilder().buildFromPatch(collector.collectPaths(emptyList(), listOf(empty.path)), 10, 10)
        assertEquals(ChangeStatus.ADDED, diff.files.single().status)
        assertEquals(0, diff.totalAdditions)
        assertTrue(diff.keyHunks.isEmpty())
    }

    @Test
    fun `partial tracker base avoids CRLF edits and excluded files retain original totals`() = repo { root ->
        val file = File(root, "crlf.txt").apply { writeText("first\r\nsecond\r\n") }
        val excluded = File(root, "excluded.txt").apply { writeText("original\n") }
        commit(root)
        file.writeText("SELECTED\r\nEXCLUDED\r\n")
        excluded.writeText("DO_NOT_INCLUDE\n")
        val inputs = collector.collectPaths(listOf(
            GitPathChange(file.path, file.path, "SELECTED\nsecond\n", "first\nsecond\n"),
            GitPathChange(excluded.path, excluded.path, "original\n", "original\n")
        ), emptyList())
        val diff = StructuredDiffBuilder().buildFromPatch(inputs, 10, 10)
        assertEquals(2, diff.totalFiles)
        assertEquals(1, diff.totalAdditions)
        assertEquals(1, diff.totalDeletions)
        assertFalse(diff.keyHunks.single().patch.contains("EXCLUDED"))
        assertEquals("excluded", diff.omissions.single().reason)
        assertEquals(1, diff.omissions.single().files)
    }

    @Test
    fun `initialized submodule selection belongs to superproject and retains gitlink diff`() = repo { source -> repo { root ->
        File(source, "file.txt").writeText("base\n"); commit(source)
        git(root, "-c", "protocol.file.allow=always", "submodule", "add", "--quiet", source.path, "module")
        commit(root)
        File(source, "file.txt").writeText("next\n"); commit(source)
        val module = File(root, "module")
        git(module, "fetch", "--quiet")
        git(module, "checkout", "--quiet", "origin/HEAD")
        git(root, "config", "diff.submodule", "log")
        val input = collector.collectPaths(listOf(GitPathChange(module.path, module.path)), emptyList()).single()
        assertEquals(root.canonicalPath, input.repositoryRoot)
        assertEquals("module", input.relativePath)
        assertNull(input.skipReason)
        assertTrue(input.patch.contains("Subproject commit"))
        assertTrue(input.patch.contains("160000"))
        val nested = File(module, "file.txt").apply { writeText("local edit\n") }
        val nestedInput = collector.collectPaths(listOf(GitPathChange(nested.path, nested.path)), emptyList()).single()
        assertEquals(module.canonicalPath, nestedInput.repositoryRoot)
        assertEquals("file.txt", nestedInput.relativePath)
        assertTrue(nestedInput.patch.contains("+local edit"))
    } }

    @Test
    fun `configured decoder handles non UTF8 untracked and unstaged rename content`() = repo { root ->
        val charset = charset("windows-1252")
        val decoding = GitDiffCollector(DiffLimits(), {}, { _, bytes ->
            GitDiffCollector.decodeText(bytes, charset)
        })
        val old = File(root, "old.txt").apply { writeBytes("café\n".toByteArray(charset)) }
        commit(root)
        val renamed = File(root, "renamed.txt")
        assertTrue(old.renameTo(renamed))
        renamed.writeBytes("crème\n".toByteArray(charset))
        val fresh = File(root, "new.txt").apply { writeBytes("naïve\n".toByteArray(charset)) }
        val inputs = decoding.collectPaths(listOf(GitPathChange(old.path, renamed.path)), listOf(fresh.path))
        assertTrue(inputs[0].patch.contains("-café\n+crème"))
        assertTrue(inputs[1].patch.contains("+naïve"))
        assertFalse(inputs.any { '\uFFFD' in it.patch })
    }

    @Test
    fun `BOM is consumed and invalid text fails without replacement characters`() = repo { root ->
        val utf8 = File(root, "utf8.txt").apply { writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello\n".toByteArray()) }
        val utf16 = File(root, "utf16.txt").apply { writeBytes("héllo\n".toByteArray(Charsets.UTF_16)) }
        val inputs = collector.collectPaths(emptyList(), listOf(utf8.path, utf16.path))
        assertTrue(inputs[0].patch.contains("+hello\n"))
        assertTrue(inputs[1].patch.contains("+héllo\n"))
        assertTrue(inputs.all { it.skipReason == null && '\uFEFF' !in it.patch })
        val invalid = File(root, "invalid.txt").apply { writeBytes(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertFailsWith<GitCollectionException> { collector.collectPaths(emptyList(), listOf(invalid.path)) }
    }

    @Test
    fun `partial snapshot preserves executable mode even with all text edits excluded`() = repo { root ->
        git(root, "config", "core.filemode", "true")
        val file = File(root, "script.sh").apply { writeText("original\n") }
        commit(root)
        assertTrue(file.setExecutable(true, false))
        file.writeText("EXCLUDED\n")
        val input = collector.collectPaths(listOf(GitPathChange(file.path, file.path,
            selectedContent = "original\n", selectedBaseContent = "original\n")), emptyList()).single()
        assertNull(input.skipReason)
        assertTrue(input.patch.contains("old mode 100644\nnew mode 100755\n"))
        assertFalse(input.patch.contains("EXCLUDED"))
        assertFalse(input.patch.contains("@@"))
    }

    @Test
    fun `partial snapshot retains mode and selected hunks but excludes other edits`() = repo { root ->
        git(root, "config", "core.filemode", "true")
        val file = File(root, "script.sh").apply { writeText("first\nsecond\n") }; commit(root)
        assertTrue(file.setExecutable(true, false))
        file.writeText("SELECTED\nEXCLUDED\n")
        val input = collector.collectPaths(listOf(GitPathChange(file.path, file.path,
            "SELECTED\nsecond\n", "first\nsecond\n")), emptyList()).single()
        assertTrue(input.patch.contains("old mode 100644\nnew mode 100755\n"))
        assertTrue(input.patch.contains("+SELECTED"))
        assertFalse(input.patch.contains("EXCLUDED"))
    }

    @Test
    fun `partial content produces only selected changes and preserves final newline`() = repo { root ->
        val file = File(root, "file.txt").apply { writeText("first\nsecond\n") }; commit(root)
        file.writeText("SELECTED\nEXCLUDED\n")
        val input = collector.collectPaths(listOf(GitPathChange(file.path, file.path,
            selectedContent = "SELECTED\nsecond\n")), emptyList()).single()
        assertTrue(input.patch.contains("+SELECTED"))
        assertFalse(input.patch.contains("EXCLUDED"))
    }
}
