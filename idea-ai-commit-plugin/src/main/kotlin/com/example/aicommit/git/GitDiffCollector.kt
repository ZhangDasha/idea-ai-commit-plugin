package com.example.aicommit.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.FilePath
import org.jetbrains.annotations.VisibleForTesting
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Git diff 收集器：从已勾选变更生成 diff 文本
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
     * 轻量收集：仅输出变更文件路径列表（用于基础测试和调试）
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
     * 完整收集：生成 diff 文本（Action 实际调用此方法）
     * 通过 Change 的 before/after revision 获取内容差异
     */
    fun collectWithUnifiedDiff(
        project: Project,
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>
    ): String {
        val patchBuilder = StringBuilder()

        // 已版本控制文件：读取 before/after 内容生成简易 diff
        includedChanges.forEach { change ->
            val filePath = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: return@forEach
            if (shouldSkipFile(filePath)) return@forEach

            patchBuilder.append("--- a/").append(filePath).append('\n')
            patchBuilder.append("+++ b/").append(filePath).append('\n')

            val beforeContent = contentOf(change.beforeRevision)
            val afterContent = contentOf(change.afterRevision)

            if (beforeContent == null && afterContent != null) {
                // 新增文件
                afterContent.lines().forEach { patchBuilder.append('+').append(it).append('\n') }
            } else if (afterContent == null && beforeContent != null) {
                // 删除文件
                beforeContent.lines().forEach { patchBuilder.append('-').append(it).append('\n') }
            } else if (beforeContent != null && afterContent != null) {
                // 修改文件：简单逐行对比
                val beforeLines = beforeContent.lines()
                val afterLines = afterContent.lines()
                beforeLines.forEach { patchBuilder.append('-').append(it).append('\n') }
                afterLines.forEach { patchBuilder.append('+').append(it).append('\n') }
            }
            patchBuilder.append('\n')
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

    /** 安全读取 ContentRevision 内容 */
    private fun contentOf(revision: ContentRevision?): String? {
        return try {
            revision?.content
        } catch (_: Exception) {
            null
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
