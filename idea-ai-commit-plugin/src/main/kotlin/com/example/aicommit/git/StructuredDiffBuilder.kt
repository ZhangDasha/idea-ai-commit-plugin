package com.example.aicommit.git

import com.example.aicommit.model.*
import com.fasterxml.jackson.databind.ObjectMapper

internal data class PatchInput(
    val relativePath: String,
    val status: ChangeStatus,
    val patch: String,
    val additions: Int = patchCounts(patch).first,
    val deletions: Int = patchCounts(patch).second,
    val oldPath: String? = null,
    val repositoryRoot: String? = null,
    val skipReason: String? = null
)

/** Count only hunk bodies; +++/--- may be actual added/deleted source lines. */
private fun patchCounts(patch: String): Pair<Int, Int> {
    var inHunk = false
    var additions = 0
    var deletions = 0
    patch.lineSequence().forEach { line ->
        when {
            line.startsWith("@@ ") -> inHunk = true
            line.startsWith("diff --git ") -> inHunk = false
            inHunk && line.startsWith('+') -> additions++
            inHunk && line.startsWith('-') -> deletions++
        }
    }
    return additions to deletions
}

internal fun isSensitivePath(path: String): Boolean = path.split('/', '\\').any { segment ->
    val name = segment.lowercase()
    name == ".env" || name.startsWith(".env.") || name.endsWith(".pem") || name.endsWith(".key") ||
        name.contains("credentials") || name == "id_rsa" || name == "id_ed25519"
}

/** Whole hunks share one budget including the JSON inventory and exclusion metadata. */
class StructuredDiffBuilder(private val limits: DiffLimits = DiffLimits()) {
    private val mapper = ObjectMapper()

    internal fun buildFromPatch(inputs: List<PatchInput>, maxFiles: Int, maxHunksPerFile: Int): StructuredDiff {
        require(inputs.isNotEmpty()) { "No selected Git changes" }
        require(maxFiles >= 0 && maxHunksPerFile >= 0)
        val omissions = linkedMapOf<String, DiffOmission>()
        fun omit(reason: String, files: Int = 0, hunks: Int = 0) {
            val previous = omissions[reason] ?: DiffOmission(reason)
            omissions[reason] = previous.copy(files = previous.files + files, hunks = previous.hunks + hunks)
        }
        val safe = inputs.filter { input ->
            val reason = if (isSensitivePath(input.relativePath) || input.oldPath?.let(::isSensitivePath) == true)
                "sensitive" else input.skipReason
            if (reason != null) omit(reason, files = 1)
            reason == null
        }
        val allHunks = safe.map { extractHunks(it.patch) }
        val files = safe.map { input ->
            ChangedFileSummary(input.relativePath, input.status, input.additions, input.deletions,
                input.relativePath.substringAfterLast('.', ""), detectLanguage(input.relativePath),
                detectModule(input.relativePath), input.oldPath, input.repositoryRoot,
                patchMode(input.patch, "old"), patchMode(input.patch, "new"))
        }.toMutableList()
        val candidates = mutableListOf<FileHunk>()
        var filesWithHunks = 0
        safe.zip(allHunks).forEach { (input, hunks) ->
            if (hunks.isNotEmpty() && filesWithHunks++ >= maxFiles) {
                omit("maxFiles", hunks = hunks.size)
            } else {
                if (hunks.size > maxHunksPerFile) omit("maxHunksPerFile", hunks = hunks.size - maxHunksPerFile)
                hunks.take(maxHunksPerFile).forEach { patch ->
                    candidates.add(FileHunk(input.relativePath, patch.substringBefore('\n'), patch, input.repositoryRoot))
                }
            }
        }
        val selected = mutableListOf<FileHunk>()
        omit("maxChars", hunks = candidates.size)
        fun result(): StructuredDiff {
            val recorded = omissions.values.filter { it.files > 0 || it.hunks > 0 }
            return StructuredDiff(files.toList(), selected.toList(), recorded.isNotEmpty(), inputs.size,
                safe.sumOf { it.additions }, safe.sumOf { it.deletions }, allHunks.sumOf { it.size }, recorded)
        }
        fun fits() = mapper.writeValueAsString(result()).length <= limits.maxChars
        // Keep all safe summaries ahead of any hunk; only remove summaries if metadata itself overflows.
        while (!fits() && files.isNotEmpty()) {
            files.removeAt(files.lastIndex)
            omit("maxChars", files = 1)
        }
        require(fits()) { "maxChars is too small for structured diff totals and omission metadata" }
        val retained = files.map { it.repositoryRoot to it.relativePath }.toSet()
        for (hunk in candidates) {
            if (hunk.repositoryRoot to hunk.relativePath !in retained) continue
            selected.add(hunk)
            omit("maxChars", hunks = -1)
            if (!fits()) {
                selected.removeAt(selected.lastIndex)
                omit("maxChars", hunks = 1)
            }
        }
        return result()
    }

    private fun patchMode(patch: String, side: String): String? =
        patch.lineSequence().takeWhile { !it.startsWith("@@ ") }
            .firstOrNull { it.matches(Regex("$side mode [0-7]{6}")) }?.substringAfterLast(' ')

    /** Only an obvious directory above src supplies a module; package namespaces do not. */
    private fun detectModule(path: String): String? {
        val parts = path.split('/')
        val src = parts.indexOf("src")
        return if (src > 0) parts[src - 1].takeUnless { it in setOf("main", "test", "java", "kotlin") } else null
    }

    private fun detectLanguage(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "md" -> "Markdown"
        "xml" -> "XML"
        "js", "jsx" -> "JavaScript"
        "ts", "tsx" -> "TypeScript"
        "py" -> "Python"
        else -> "PlainText"
    }

    private fun extractHunks(patch: String): List<String> {
        val starts = Regex("(?m)^@@ .*@@[^\\n]*(?:\\n|$)").findAll(patch).map { it.range.first }.toList()
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: patch.length
            val nextFile = patch.indexOf("\ndiff --git ", start)
            patch.substring(start, if (nextFile in start until end) nextFile + 1 else end)
        }
    }
}
