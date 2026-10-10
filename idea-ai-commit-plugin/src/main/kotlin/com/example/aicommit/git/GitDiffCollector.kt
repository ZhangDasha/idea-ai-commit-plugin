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
import java.io.File
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
    private val checkCanceled: () -> Unit
) {
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
            val skip = skipReason(absolute, change.beforePath, unversioned = false)
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
                    ?: execAndRead(listOf("git", "show", "$base:$old"), root)
                val after = change.selectedContent ?: if (Files.isSymbolicLink(Path.of(absolute)))
                    Files.readSymbolicLink(Path.of(absolute)).toString() else Files.readString(Path.of(absolute))
                contentPatch(root, if (effectiveStatus == ChangeStatus.ADDED) path else old ?: path,
                    path, before, after, added = effectiveStatus == ChangeStatus.ADDED)
            } else {
                execAndRead(listOf("git", "diff", "--no-color", "--no-ext-diff", "--no-textconv",
                    "--find-renames", "--unified=${limits.contextLines}", base, "--") + paths, root)
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
            val patch = if (skip == null) contentPatch(root, path, path, "", Files.readString(Path.of(absolute)), added = true) else ""
            inputs.add(PatchInput(path, ChangeStatus.ADDED, patch, repositoryRoot = root.path, skipReason = skip))
        }
        if (inputs.isEmpty()) throw GitCollectionException("No included Git changes to describe")
        return inputs
    }

    private fun skipReason(path: String, beforePath: String?, unversioned: Boolean): String? {
        if (listOfNotNull(path, beforePath).any(::isSensitivePath)) return "sensitive"
        if (listOfNotNull(path, beforePath).any(::shouldSkipFile)) return "binary"
        val nio = Path.of(path)
        if (!Files.exists(nio, LinkOption.NOFOLLOW_LINKS)) {
            if (unversioned) throw GitCollectionException("Selected untracked file is missing: $path")
            return null // deleted tracked path
        }
        if (Files.isSymbolicLink(nio)) return if (unversioned) "symbolicLink" else null
        if (!Files.isRegularFile(nio)) throw GitCollectionException("Selected path is not a file: $path")
        if (unversioned && Files.size(nio) > limits.maxUnversionedBytes) return "size"
        Files.newInputStream(nio).use { if (it.readNBytes(8000).any { byte -> byte == 0.toByte() }) return "binary" }
        return null
    }

    /** Git supplies real hunks, including no-newline markers, for untracked and selected partial content. */
    private fun contentPatch(root: File, old: String, path: String, before: String, after: String, added: Boolean = false): String {
        val temp = Files.createTempDirectory("aicommit-diff-")
        try {
            val left = temp.resolve("before"); val right = temp.resolve("after")
            Files.writeString(left, before); Files.writeString(right, after)
            val cmd = listOf("git", "diff", "--no-index", "--no-color", "--no-ext-diff", "--no-textconv",
                "--unified=${limits.contextLines}", "--", left.toString(), right.toString())
            val result = execute(cmd, root)
            if (result.exitCode !in 0..1) throw commandFailure(cmd, result)
            if (result.exitCode == 0) return when {
                added -> "diff --git a/$path b/$path\nnew file mode 100644\n"
                old != path -> "diff --git a/$old b/$path\nrename from $old\nrename to $path\n"
                else -> ""
            }
            val start = result.stdout.indexOf("\n@@ ")
            if (start < 0) throw GitCollectionException("Cannot create text hunks for $path")
            return buildString {
                append("diff --git a/").append(old).append(" b/").append(path).append('\n')
                if (added) append("new file mode 100644\n")
                if (old != path) append("rename from ").append(old).append("\nrename to ").append(path).append('\n')
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
        // A selected directory symlink is itself a Git entry. Resolve its parent, never its external target.
        var directory: File? = File(path).absoluteFile.let {
            if (Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)) it else it.parentFile
        }
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

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

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
        val stdout = CompletableFuture.supplyAsync { process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
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
        val BINARY_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".pdf",
            ".jar", ".zip", ".class", ".exe", ".dll", ".so", ".dylib", ".woff", ".woff2")
    }
}
