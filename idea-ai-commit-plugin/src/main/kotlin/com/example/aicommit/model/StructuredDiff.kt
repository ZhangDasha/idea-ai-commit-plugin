package com.example.aicommit.model

/** File status uses Git semantics, including a rename's before and after paths. */
enum class ChangeStatus { ADDED, MODIFIED, DELETED, RENAMED }

data class ChangedFileSummary(
    val relativePath: String,
    val status: ChangeStatus,
    val additions: Int,
    val deletions: Int,
    val extension: String,
    val language: String,
    val module: String?,
    val oldPath: String? = null,
    val repositoryRoot: String? = null
)

data class FileHunk(
    val relativePath: String,
    val header: String,
    val patch: String,
    val repositoryRoot: String? = null
)

/** Aggregated exclusions keep metadata bounded even for thousands of skipped files. */
data class DiffOmission(val reason: String, val files: Int = 0, val hunks: Int = 0)

/**
 * Totals describe the original selection, before filtering/budgeting. Line and hunk
 * totals cover readable, safe patches; filtered contents are deliberately not read.
 * maxFiles limits files with key hunks, not the inventory of safe file summaries.
 */
data class StructuredDiff(
    val files: List<ChangedFileSummary>,
    val keyHunks: List<FileHunk>,
    val truncated: Boolean,
    val totalFiles: Int,
    val totalAdditions: Int,
    val totalDeletions: Int,
    val totalHunks: Int = 0,
    val omissions: List<DiffOmission> = emptyList()
)
