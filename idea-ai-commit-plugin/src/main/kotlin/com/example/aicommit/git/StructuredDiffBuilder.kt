package com.example.aicommit.git

import com.example.aicommit.model.ChangeStatus
import com.example.aicommit.model.ChangedFileSummary
import com.example.aicommit.model.FileHunk
import com.example.aicommit.model.StructuredDiff

/**
 * 单文件 patch 输入。
 * additions/deletions 默认从 patch 行统计（+/- 开头且非 hunk 头 +++/---）。
 */
internal data class PatchInput(
    val relativePath: String,
    val status: ChangeStatus,
    val patch: String,
    val additions: Int = patch.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") },
    val deletions: Int = patch.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
)

/**
 * 结构化 diff 构建器：敏感过滤 + 文件/hunk 预算裁剪 + 模块/语言推断。
 * 不负责 LLM 调用与 UI 写回。
 */
class StructuredDiffBuilder(private val limits: DiffLimits = DiffLimits()) {

    /** 默认过滤的敏感文件名片段 */
    private val sensitivePatterns = listOf(".env", ".pem", ".key", "credentials")

    /**
     * 从 patch 输入列表构建结构化 diff。
     * @param maxFiles 文件数量预算（超出则裁剪并标记 truncated）
     * @param maxHunksPerFile 每文件 hunk 数量预算
     */
    internal fun buildFromPatch(inputs: List<PatchInput>, maxFiles: Int, maxHunksPerFile: Int): StructuredDiff {
        // 先过滤敏感文件，truncated 仅反映是否因 maxFiles 裁剪（不反映敏感过滤）
        val safe = inputs.filterNot { isSensitive(it.relativePath) }
        val truncated = safe.size > maxFiles
        val filtered = safe.take(maxFiles)

        val files = filtered.map { input ->
            ChangedFileSummary(
                relativePath = input.relativePath,
                status = input.status,
                additions = input.additions,
                deletions = input.deletions,
                extension = input.relativePath.substringAfterLast('.', ""),
                language = detectLanguage(input.relativePath),
                module = detectModule(input.relativePath)
            )
        }

        // 每文件提取有限 hunk，按 maxChars 截断
        val hunks = filtered.flatMap { input ->
            extractHunks(input.patch)
                .take(maxHunksPerFile)
                .map { hunkText ->
                    FileHunk(
                        relativePath = input.relativePath,
                        header = hunkText.lineSequence().firstOrNull().orEmpty(),
                        patch = hunkText.take(limits.maxChars)
                    )
                }
        }

        return StructuredDiff(
            files = files,
            keyHunks = hunks,
            truncated = truncated,
            totalFiles = files.size,
            totalAdditions = files.sumOf { it.additions },
            totalDeletions = files.sumOf { it.deletions }
        )
    }

    /** 敏感文件判定：路径包含任一敏感片段即过滤 */
    private fun isSensitive(path: String): Boolean =
        sensitivePatterns.any { path.contains(it, ignoreCase = true) }

    /** 模块推断：优先取 aicommit 下一级目录，其次 src 后两级目录 */
    private fun detectModule(path: String): String? =
        path.split('/').windowed(2, 1).firstOrNull { it.first() == "aicommit" }?.getOrNull(1)
            ?: path.split('/').dropWhile { it != "src" }.drop(2).firstOrNull()

    /** 语言推断：按扩展名映射 */
    private fun detectLanguage(path: String): String = when {
        path.endsWith(".kt") -> "Kotlin"
        path.endsWith(".java") -> "Java"
        path.endsWith(".md") -> "Markdown"
        path.endsWith(".xml") -> "XML"
        else -> "PlainText"
    }

    /** 按 @@ 头切分 patch 为独立 hunk 文本（每段以 @@ 行起始） */
    private fun extractHunks(patch: String): List<String> {
        val hunks = mutableListOf<String>()
        val current = StringBuilder()
        var inHunk = false
        patch.lineSequence().forEach { line ->
            if (line.startsWith("@@")) {
                // 遇到新 hunk 头，收尾上一个
                if (inHunk && current.isNotEmpty()) hunks.add(current.toString())
                current.setLength(0)
                current.append(line).append('\n')
                inHunk = true
            } else if (inHunk) {
                current.append(line).append('\n')
            }
        }
        if (inHunk && current.isNotEmpty()) hunks.add(current.toString())
        return hunks
    }
}
