package com.example.aicommit.model

/**
 * 结构化 diff 数据模型。
 * 替代整段原始 diff 直推模型，提供分层输入：文件摘要 + 关键 hunk + 统计信息。
 */

/** 变更状态，对齐 git diff 的文件状态语义 */
enum class ChangeStatus {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED
}

/** 单文件摘要：路径、状态、增删行数、扩展名、推断语言、所属模块 */
data class ChangedFileSummary(
    val relativePath: String,
    val status: ChangeStatus,
    val additions: Int,
    val deletions: Int,
    val extension: String,
    val language: String,
    val module: String?
)

/** 关键 hunk：来自某文件的一段 patch 片段，供模型聚焦查看 */
data class FileHunk(
    val relativePath: String,
    val header: String,
    val patch: String
)

/**
 * 结构化 diff 顶层对象
 * @param truncated 是否因文件数量预算被裁剪
 */
data class StructuredDiff(
    val files: List<ChangedFileSummary>,
    val keyHunks: List<FileHunk>,
    val truncated: Boolean,
    val totalFiles: Int,
    val totalAdditions: Int,
    val totalDeletions: Int
)
