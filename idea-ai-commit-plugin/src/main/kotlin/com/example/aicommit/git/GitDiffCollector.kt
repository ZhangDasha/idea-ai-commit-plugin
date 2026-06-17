package com.example.aicommit.git

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
