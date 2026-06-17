package com.example.aicommit.git

import com.example.aicommit.model.ChangeStatus
import com.example.aicommit.model.StructuredDiff
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.FilePath
import java.io.File
import org.jetbrains.annotations.VisibleForTesting
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Git diff 收集器：从已勾选变更生成真实 unified diff
 */
class GitDiffCollector(private val limits: DiffLimits = DiffLimits()) {

    /** 按 maxChars 截断，防止 diff 过长 */
    fun truncate(raw: String): String {
        if (raw.length <= limits.maxChars) return raw
        return raw.substring(0, limits.maxChars)
    }

    /** 判断是否应跳过二进制/大文件 */
    fun shouldSkipFile(path: String): Boolean {
        return BINARY_EXTENSIONS.any { ext -> path.endsWith(ext, ignoreCase = true) }
    }

    /**
     * 轻量收集：仅输出变更文件路径列表
     */
    fun collect(
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>
    ): String {
        val builder = StringBuilder()
        includedChanges.forEach { change ->
            builder.append("[CHANGE] ")
                .append(change.virtualFile?.path.orEmpty())
                .append('\n')
        }
        unversionedFiles.forEach { file ->
            builder.append("[ADD] ")
                .append(file.path)
                .append('\n')
        }
        return truncate(builder.toString())
    }

    /**
     * 完整收集：通过真实的 git diff 命令生成 unified diff
     * git diff HEAD 同时覆盖已暂存 + 未暂存变更，避免漏文件
     */
    fun collectWithUnifiedDiff(
        project: Project,
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>
    ): String {
        val gitRoot = findGitRoot(project) ?: return ""
        val patchBuilder = StringBuilder()

        // 从已勾选变更提取文件路径，跳过二进制文件
        val changePaths = includedChanges.mapNotNull { change ->
            change.virtualFile?.path ?: change.beforeRevision?.file?.path
        }.filter { path -> !shouldSkipFile(path) }.toTypedArray()

        if (changePaths.isNotEmpty()) {
            // git diff HEAD 同时覆盖已暂存 + 未暂存变更，避免漏文件
            val diff = gitDiff(gitRoot, "HEAD", "--unified=${limits.contextLines}", *changePaths)
            if (diff.isNotBlank()) {
                patchBuilder.append(diff)
                if (!diff.endsWith('\n')) patchBuilder.append('\n')
            }
        }

        // 未跟踪文件：直接读取内容作为新增
        unversionedFiles.forEach { file ->
            if (shouldSkipFile(file.path)) return@forEach
            val nioPath = Paths.get(file.path)
            if (!Files.exists(nioPath) || Files.size(nioPath) > limits.maxUnversionedBytes) return@forEach
            patchBuilder.append("--- /dev/null\n")
            patchBuilder.append("+++ b/").append(file.path).append('\n')
            Files.readAllLines(nioPath).forEach { line ->
                patchBuilder.append('+').append(line).append('\n')
            }
            patchBuilder.append('\n')
        }

        return truncate(patchBuilder.toString())
    }

    /**
     * 结构化收集：从勾选变更构建 StructuredDiff（文件摘要 + 关键 hunk + 预算裁剪）。
     * 复用统一 diff 与未跟踪文件 patch 化逻辑，产出 PatchInput 后交 StructuredDiffBuilder 裁剪。
     */
    fun collectStructured(
        project: Project,
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>,
        maxFiles: Int,
        maxHunksPerFile: Int
    ): StructuredDiff {
        val gitRoot = findGitRoot(project)
            ?: return StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0)
        val patchInputs = collectPatchInputs(gitRoot, includedChanges, unversionedFiles)
        return StructuredDiffBuilder(limits).buildFromPatch(patchInputs, maxFiles, maxHunksPerFile)
    }

    /** 汇总已跟踪 + 未跟踪文件的 patch 输入，跳过二进制/大文件，统一为相对路径 */
    private fun collectPatchInputs(
        gitRoot: File,
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>
    ): List<PatchInput> {
        val rootPath = File(gitRoot.path).toPath()

        // 已跟踪变更：逐文件取 unified diff，按 before/after 推断状态
        val tracked = includedChanges.mapNotNull { change ->
            val path = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: return@mapNotNull null
            if (shouldSkipFile(path)) return@mapNotNull null
            val relativePath = rootPath.relativize(File(path).toPath()).toString().replace('\\', '/')
            val patch = gitDiff(gitRoot, "HEAD", "--unified=${limits.contextLines}", "--", relativePath)
            val status = when {
                change.beforeRevision == null && change.afterRevision != null -> ChangeStatus.ADDED
                change.afterRevision == null -> ChangeStatus.DELETED
                else -> ChangeStatus.MODIFIED
            }
            PatchInput(relativePath, status, patch)
        }

        // 未跟踪文件：直接读取内容构造新增 patch
        val untracked = unversionedFiles.mapNotNull { file ->
            if (shouldSkipFile(file.path)) return@mapNotNull null
            val nioPath = Paths.get(file.path)
            if (!Files.exists(nioPath) || Files.size(nioPath) > limits.maxUnversionedBytes) return@mapNotNull null
            val relativePath = rootPath.relativize(nioPath).toString().replace('\\', '/')
            val lines = Files.readAllLines(nioPath)
            val patch = buildString {
                append("--- /dev/null\n")
                append("+++ b/").append(relativePath).append('\n')
                append("@@ -0,0 +1,").append(lines.size).append(" @@\n")
                lines.forEach { append('+').append(it).append('\n') }
            }
            PatchInput(relativePath, ChangeStatus.ADDED, patch)
        }

        return tracked + untracked
    }

    /** 执行 git diff 命令，返回标准 diff 文本 */
    private fun gitDiff(gitRoot: File, vararg args: String): String {
        return try {
            val cmd = listOf("git", "diff", "--no-color") + args.toList()
            execAndRead(cmd, gitRoot)
        } catch (_: Exception) {
            ""
        }
    }

    /** 通过 git rev-parse 找到仓库根目录 */
    @VisibleForTesting
    fun findGitRoot(project: Project): File? {
        val baseDir = project.basePath ?: return null
        val root = execAndRead(listOf("git", "rev-parse", "--show-toplevel"), File(baseDir))
        return if (root.isNotBlank()) File(root) else null
    }

    /** 执行外部命令并读取标准输出 */
    @VisibleForTesting
    fun execAndRead(cmd: List<String>, workDir: File): String {
        return try {
            val process = ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        /** 需要跳过的二进制文件扩展名 */
        @VisibleForTesting
        val BINARY_EXTENSIONS = setOf(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico",
            ".jar", ".zip", ".class", ".exe", ".dll", ".so", ".dylib"
        )
    }
}
