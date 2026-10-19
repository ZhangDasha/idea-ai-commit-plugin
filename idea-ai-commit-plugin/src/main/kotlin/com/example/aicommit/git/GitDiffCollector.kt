package com.example.aicommit.git

import com.example.aicommit.model.ChangeStatus
import com.example.aicommit.model.StructuredDiff
import com.intellij.diff.util.Side
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListChange
import com.intellij.openapi.vcs.ex.PartialLocalLineStatusTracker
import com.intellij.openapi.vcs.impl.PartialChangesUtil
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GitCollectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal data class GitPathChange(
    val beforePath: String?,
    val afterPath: String?,
    val selectedContent: String? = null,
    val selectedBaseContent: String? = null
)

/** Selected paths are resolved individually, so project basePath need not be a Git root. */
class GitDiffCollector internal constructor(
    private val limits: DiffLimits,
    private val checkCanceled: () -> Unit,
    private val fileReader: (Path, ByteArray) -> String
) {
    internal constructor(limits: DiffLimits, checkCanceled: () -> Unit) : this(limits, checkCanceled, ::readFileText)
    constructor(limits: DiffLimits = DiffLimits()) : this(limits, { ProgressManager.checkCanceled() })
    fun truncate(raw: String): String = raw.take(limits.maxChars)

    fun shouldSkipFile(path: String): Boolean = BINARY_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }

    fun collect(includedChanges: List<Change>, unversionedFiles: List<FilePath>): String {
        require(includedChanges.isNotEmpty() || unversionedFiles.isNotEmpty()) { "No selected changes" }
        return truncate(buildString {
            includedChanges.forEach { change ->
                val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path
                    ?: throw GitCollectionException("Selected change has no file path")
                resolveRoot(path)
                append("[CHANGE] ").append(path).append('\n')
            }
            unversionedFiles.forEach { resolveRoot(it.path); append("[ADD] ").append(it.path).append('\n') }
        })
    }

    fun collectWithUnifiedDiff(
        project: Project,
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>
    ): String = truncate(collectInputs(project, includedChanges, unversionedFiles, null).joinToString("") { it.patch })

    /**
     * Pass the selected changelist IDs when changes are not ChangeListChange wrappers.
     * With an active partial tracker, ambiguous list ownership is rejected; never fall
     * back to a whole-file diff when selected tracker content is unavailable.
     */
    fun collectStructured(
        project: Project,
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>,
        maxFiles: Int = 50,
        maxHunksPerFile: Int = 5,
        selectedChangeListIds: List<String>? = null
    ): StructuredDiff = StructuredDiffBuilder(limits).buildFromPatch(
        collectInputs(project, includedChanges, unversionedFiles, selectedChangeListIds), maxFiles, maxHunksPerFile)

    private fun collectInputs(
        project: Project,
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>,
        selectedChangeListIds: List<String>?
    ): List<PatchInput> {
        val paths = includedChanges.groupBy { it.beforeRevision?.file?.path to it.afterRevision?.file?.path }
            .map { (filePaths, changes) ->
                checkCanceled()
                val (before, after) = filePaths
                val wrappedIds = changes.filterIsInstance<ChangeListChange>().map { it.changeListId }.distinct()
                val ids = wrappedIds.takeIf { it.isNotEmpty() } ?: selectedChangeListIds
                val selected = if (listOfNotNull(before, after).any { isSensitivePath(it) || shouldSkipFile(it) }) null
                    else selectedSnapshot(project, changes.first(), ids)
                GitPathChange(before, after, selected?.after, selected?.before)
            }
        return collectPaths(paths, unversionedFiles.map { it.path })
    }

    private data class PartialSnapshot(val before: String, val after: String)

    private fun selectedSnapshot(project: Project, change: Change, selectedIds: List<String>?): PartialSnapshot? =
        ApplicationManager.getApplication().runReadAction(Computable {
            val tracker = PartialChangesUtil.getPartialTracker(project, change) ?: return@Computable null
            tracker.readLock {
                // IDEA documents normalize line separators; use the matching tracker base
                // so CRLF files do not turn excluded lines into apparent edits.
                PartialSnapshot(tracker.vcsDocument.text, selectedContent(tracker, selectedIds))
            }
        })

    internal fun selectedContent(tracker: PartialLocalLineStatusTracker, selectedIds: List<String>?): String {
        val ids = selectedIds ?: tracker.getAffectedChangeListsIds().takeIf { it.size == 1 }
            ?: throw GitCollectionException("Partial change requires selected changelist IDs")
        if (ids.isEmpty()) throw GitCollectionException("Partial change has no selected changelist")
        return tracker.getChangesToBeCommitted(Side.LEFT, ids, true)
            ?: throw GitCollectionException("Selected partial changes are not ready; retry after the tracker updates")
    }

    internal fun collectPaths(changes: List<GitPathChange>, unversionedPaths: List<String>): List<PatchInput> {
        require(changes.isNotEmpty() || unversionedPaths.isNotEmpty()) { "No selected Git changes" }
        val baselines = mutableMapOf<File, String>()
        fun baseline(root: File): String = baselines.getOrPut(root) {
            val head = execute(listOf("git", "rev-parse", "--verify", "--quiet", "HEAD"), root)
            when (head.exitCode) {
                0 -> head.stdout.trim()
                1 -> execAndRead(listOf("git", "hash-object", "-t", "tree", "--stdin"), root).trim()
                else -> throw commandFailure(listOf("git", "rev-parse", "HEAD"), head)
            }
        }
        val inputs = mutableListOf<PatchInput>()
        val seen = mutableSetOf<Pair<String, String>>()
        changes.forEach { change ->
            checkCanceled()
            val absolute = change.afterPath ?: change.beforePath
                ?: throw GitCollectionException("Selected change has no file path")
            val root = resolveRoot(absolute)
            if (change.beforePath != null && resolveRoot(change.beforePath) != root)
                throw GitCollectionException("A rename cannot cross Git repositories")
            val path = relative(root, absolute)
            if (!seen.add(root.path to path)) return@forEach
            val old = change.beforePath?.let { relative(root, it) }
            val status = when {
                change.beforePath == null -> ChangeStatus.ADDED
                change.afterPath == null -> ChangeStatus.DELETED
                old != path -> ChangeStatus.RENAMED
                else -> ChangeStatus.MODIFIED
            }
            val skip = skipReason(absolute, change.beforePath, unversioned = false,
                gitlink = isGitlink(root, absolute))
            if (skip != null) {
                inputs.add(PatchInput(path, status, "", oldPath = old.takeIf { it != path }, repositoryRoot = root.path, skipReason = skip))
                return@forEach
            }
            val base = baseline(root)
            val beforeExists = old != null && execAndRead(
                listOf("git", "ls-tree", "--name-only", "-z", base, "--", old), root
            ).isNotEmpty()
            val effectiveStatus = if (change.afterPath != null && !beforeExists) ChangeStatus.ADDED else status
            val paths = listOfNotNull(old, path).distinct()
            val indexedRename = status == ChangeStatus.RENAMED &&
                execute(listOf("git", "ls-files", "--error-unmatch", "--", path), root).exitCode == 0
            val patch = if (change.selectedContent != null || (status == ChangeStatus.RENAMED && !indexedRename)) {
                val before = if (effectiveStatus == ChangeStatus.ADDED) "" else change.selectedBaseContent
                    ?: readText(Path.of(absolute), executeBlob(root, "$base:$old"))
                val after = change.selectedContent ?: if (Files.isSymbolicLink(Path.of(absolute)))
                    Files.readSymbolicLink(Path.of(absolute)).toString() else readText(Path.of(absolute))
                // Raw diff carries modes but no content; excluded worktree hunks never enter the snapshot.
                val raw = execAndRead(listOf("git", "diff", "--raw", "--no-abbrev", "--no-ext-diff",
                    base, "--") + paths, root)
                val modes = raw.lineSequence().filter { it.startsWith(":") }.map { it.split(' ', '\t') }.toList()
                val beforeMode = if (effectiveStatus == ChangeStatus.ADDED) null else execAndRead(
                    listOf("git", "ls-tree", base, "--", old ?: path), root).take(6)
                val afterMode = modes.firstOrNull { it.getOrNull(1) != "000000" }?.getOrNull(1)
                    ?: beforeMode ?: "100644"
                contentPatch(root, if (effectiveStatus == ChangeStatus.ADDED) path else old ?: path,
                    path, before, after, added = effectiveStatus == ChangeStatus.ADDED,
                    beforeMode = beforeMode, afterMode = afterMode)
            } else {
                execAndRead(listOf("git", "diff", "--no-color", "--no-ext-diff", "--no-textconv",
                    "--find-renames", "--submodule=short", "--unified=${limits.contextLines}", base, "--") + paths, root)
            }
            if (patch.isEmpty()) {
                // Fully excluded partial selections are allowed, but unchanged whole-file selections are stale.
                if (change.selectedContent == null) throw GitCollectionException("No Git diff for selected path: $path")
            }
            val binary = patch.lineSequence().any { it.startsWith("Binary files ") || it == "GIT binary patch" }
            inputs.add(PatchInput(path, effectiveStatus, if (binary) "" else patch,
                oldPath = old.takeIf { effectiveStatus == ChangeStatus.RENAMED }, repositoryRoot = root.path,
                skipReason = when { binary -> "binary"; patch.isEmpty() -> "excluded"; else -> null }))
        }
        unversionedPaths.forEach { absolute ->
            checkCanceled()
            val root = resolveRoot(absolute)
            val path = relative(root, absolute)
            if (!seen.add(root.path to path)) return@forEach
            val skip = skipReason(absolute, null, unversioned = true)
            if (skip == null) {
                val cmd = listOf("git", "ls-files", "--error-unmatch", "--", path)
                val tracked = execute(cmd, root)
                when (tracked.exitCode) {
                    0 -> throw GitCollectionException("Selected untracked path is already tracked: $path")
                    1 -> Unit
                    else -> throw commandFailure(cmd, tracked)
                }
            }
            val patch = if (skip == null) contentPatch(root, path, path, "", readText(Path.of(absolute)), added = true) else ""
            inputs.add(PatchInput(path, ChangeStatus.ADDED, patch, repositoryRoot = root.path, skipReason = skip))
        }
        if (inputs.isEmpty()) throw GitCollectionException("No included Git changes to describe")
        return inputs
    }

    private fun skipReason(path: String, beforePath: String?, unversioned: Boolean, gitlink: Boolean = false): String? {
        if (listOfNotNull(path, beforePath).any(::isSensitivePath)) return "sensitive"
        if (listOfNotNull(path, beforePath).any(::shouldSkipFile)) return "binary"
        val nio = Path.of(path)
        if (!Files.exists(nio, LinkOption.NOFOLLOW_LINKS)) {
            if (unversioned) throw GitCollectionException("Selected untracked file is missing: $path")
            return null // deleted tracked path
        }
        if (Files.isSymbolicLink(nio)) return if (unversioned) "symbolicLink" else null
        if (gitlink) return null
        if (!Files.isRegularFile(nio)) throw GitCollectionException("Selected path is not a file: $path")
        if (unversioned && Files.size(nio) > limits.maxUnversionedBytes) return "size"
        Files.newInputStream(nio).use {
            val prefix = it.readNBytes(8000)
            // UTF-16/32 text contains NUL bytes; a Unicode BOM establishes the encoding.
            val encoding = configuredCharset(nio).name()
            val wideUnicode = encoding.startsWith("UTF-16") || encoding.startsWith("UTF-32")
            if (!wideUnicode && unicodeBom(prefix) == null && prefix.any { byte -> byte == 0.toByte() }) return "binary"
        }
        return null
    }

    /** Git supplies real hunks, including no-newline markers, for untracked and selected partial content. */
    private fun contentPatch(root: File, old: String, path: String, before: String, after: String, added: Boolean = false,
                             beforeMode: String? = null, afterMode: String = "100644"): String {
        val header = buildString {
            append("diff --git a/$old b/$path\n")
            if (added) append("new file mode $afterMode\n")
            else if (beforeMode != null && beforeMode != afterMode) append("old mode $beforeMode\nnew mode $afterMode\n")
            if (old != path) append("rename from $old\nrename to $path\n")
        }
        val hasMetadata = added || old != path || (beforeMode != null && beforeMode != afterMode)
        val temp = Files.createTempDirectory("aicommit-diff-")
        try {
            val left = temp.resolve("before"); val right = temp.resolve("after")
            Files.writeString(left, before); Files.writeString(right, after)
            val cmd = listOf("git", "diff", "--no-index", "--no-color", "--no-ext-diff", "--no-textconv",
                "--unified=${limits.contextLines}", "--", left.toString(), right.toString())
            val result = execute(cmd, root)
            if (result.exitCode !in 0..1) throw commandFailure(cmd, result)
            if (result.exitCode == 0) return if (hasMetadata) header else ""
            val start = result.stdout.indexOf("\n@@ ")
            if (start < 0) throw GitCollectionException("Cannot create text hunks for $path")
            return buildString {
                append(header)
                append(if (added) "--- /dev/null\n" else "--- a/$old\n")
                append("+++ b/").append(path).append('\n')
                append(result.stdout.substring(start + 1))
            }
        } finally {
            Files.deleteIfExists(temp.resolve("before")); Files.deleteIfExists(temp.resolve("after")); Files.deleteIfExists(temp)
        }
    }

    fun findGitRoot(project: Project): File? = project.basePath?.let { resolveRoot(it) }

    private fun resolveRoot(path: String): File {
        // Resolve a selected gitlink from its parent, including initialized submodules.
        // Files inside the submodule still resolve to the submodule's own repository.
        val selected = File(path).absoluteFile
        // A directory symlink is a Git entry; resolve its parent rather than its target.
        val selectedDirectory = Files.isDirectory(selected.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (selectedDirectory && selected.parentFile?.isDirectory == true) {
            val parentRoot = execute(listOf("git", "rev-parse", "--show-toplevel"), selected.parentFile)
            if (parentRoot.exitCode == 0) {
                val owner = File(parentRoot.stdout.trim()).canonicalFile
                if (isGitlink(owner, path)) return owner
            }
        }
        var directory: File? = selected.let { if (selectedDirectory) it else it.parentFile }
        while (directory != null && !directory.isDirectory) directory = directory.parentFile
        val workDir = directory ?: throw GitCollectionException("Cannot resolve Git directory for $path")
        val output = execAndRead(listOf("git", "rev-parse", "--show-toplevel"), workDir).trim()
        if (output.isEmpty()) throw GitCollectionException("Selected path is not in a Git worktree: $path")
        return File(output).canonicalFile
    }

    private fun relative(root: File, path: String): String {
        val file = File(path).absoluteFile
        val absolute = file.parentFile.canonicalFile.toPath().resolve(file.name).normalize()
        val rootPath = root.toPath()
        if (!absolute.startsWith(rootPath)) throw GitCollectionException("Selected path is outside repository: $path")
        return rootPath.relativize(absolute).toString().replace('\\', '/')
    }

    /** stdout is returned verbatim; stderr and unsuccessful exits are never diff text. */
    fun execAndRead(cmd: List<String>, workDir: File): String {
        val result = execute(cmd, workDir)
        if (result.exitCode != 0) throw commandFailure(cmd, result)
        return result.stdout
    }

    private fun isGitlink(root: File, path: String): Boolean {
        val relative = relative(root, path)
        return execAndRead(listOf("git", "ls-files", "--stage", "-z", "--", relative), root)
            .split('\u0000').any { it.startsWith("160000 ") && it.substringAfter('\t') == relative }
    }

    private fun executeBlob(root: File, objectName: String): ByteArray {
        val cmd = listOf("git", "show", objectName)
        val result = execute(cmd, root)
        if (result.exitCode != 0) throw commandFailure(cmd, result)
        return result.stdoutBytes
    }

    private fun readText(path: Path, bytes: ByteArray = Files.readAllBytes(path)): String = try {
        fileReader(path, bytes)
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw GitCollectionException("Cannot decode selected text using its configured charset: $path", e)
    }

    private data class ProcessResult(val exitCode: Int, val stdoutBytes: ByteArray, val stderr: String) {
        val stdout: String get() = stdoutBytes.toString(Charsets.UTF_8)
    }

    private fun commandFailure(cmd: List<String>, result: ProcessResult) =
        GitCollectionException("${cmd.take(3).joinToString(" ")} failed (${result.exitCode}): ${result.stderr.take(2000)}")

    private fun execute(cmd: List<String>, workDir: File): ProcessResult {
        checkCanceled()
        val process = try {
            ProcessBuilder(cmd).directory(workDir).apply {
                environment()["GIT_LITERAL_PATHSPECS"] = "1"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["LC_ALL"] = "C"
            }.start()
        } catch (e: java.io.IOException) { throw GitCollectionException("Cannot start ${cmd.firstOrNull()}: ${e.message}", e) }
        val stdout = CompletableFuture.supplyAsync { process.inputStream.use { it.readBytes() } }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) } }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.processTimeoutMillis)
        try {
            process.outputStream.close()
            while (true) {
                checkCanceled()
                if (System.nanoTime() >= deadline) throw GitCollectionException("${cmd.first()} timed out after ${limits.processTimeoutMillis} ms")
                if (process.waitFor(25, TimeUnit.MILLISECONDS) && stdout.isDone && stderr.isDone) break
            }
            return ProcessResult(process.exitValue(), stdout.join(), stderr.join())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GitCollectionException("${cmd.first()} was interrupted", e)
        } finally {
            // Kill descendants as well: inherited pipes must not keep reader tasks alive after cancellation.
            runCatching { process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } } }
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            stdout.cancel(true); stderr.cancel(true)
        }
    }

    companion object {
        private fun configuredCharset(path: Path): Charset =
            if (ApplicationManager.getApplication() == null) Charsets.UTF_8
            else LocalFileSystem.getInstance().findFileByNioFile(path)?.charset ?: Charsets.UTF_8

        private fun readFileText(path: Path, bytes: ByteArray): String =
            decodeText(bytes, configuredCharset(path))

        /** A BOM overrides the file charset and is consumed; malformed input must never be replaced. */
        internal fun decodeText(bytes: ByteArray, charset: Charset): String {
            val bom = unicodeBom(bytes)
            val skip = bom?.second ?: 0
            return (bom?.first ?: charset).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        }

        private fun unicodeBom(bytes: ByteArray): Pair<Charset, Int>? {
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size &&
                prefix.indices.all { bytes[it].toInt() and 0xff == prefix[it] }
            return when {
                starts(0, 0, 0xfe, 0xff) -> Charset.forName("UTF-32BE") to 4
                starts(0xff, 0xfe, 0, 0) -> Charset.forName("UTF-32LE") to 4
                starts(0xef, 0xbb, 0xbf) -> Charsets.UTF_8 to 3
                starts(0xfe, 0xff) -> Charsets.UTF_16BE to 2
                starts(0xff, 0xfe) -> Charsets.UTF_16LE to 2
                else -> null
            }
        }

        val BINARY_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".pdf",
            ".jar", ".zip", ".class", ".exe", ".dll", ".so", ".dylib", ".woff", ".woff2")
    }
}
