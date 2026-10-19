package com.example.aicommit.git

import com.example.aicommit.model.ChangeStatus
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.*

class StructuredDiffBuilderTest {
    @Test
    fun `all summaries survive file hunk limits and original totals survive truncation`() {
        val diff = StructuredDiffBuilder().buildFromPatch(
            (1..3).map { PatchInput("module$it/src/Main.kt", ChangeStatus.MODIFIED,
                "@@ -1 +1 @@\n-old\n+new\n@@ -10 +10 @@\n-x\n+y\n") }, 1, 1)
        assertEquals(3, diff.files.size)
        assertEquals(3, diff.totalFiles)
        assertEquals(6, diff.totalHunks)
        assertEquals(6, diff.totalAdditions)
        assertEquals(1, diff.keyHunks.size)
        assertTrue(diff.truncated)
        assertEquals("module1", diff.files.first().module)
        assertTrue(diff.omissions.any { it.reason == "maxFiles" && it.hunks == 4 })
        assertTrue(diff.omissions.any { it.reason == "maxHunksPerFile" && it.hunks == 1 })
    }

    @Test
    fun `global budget includes JSON escaping metadata summaries and omissions`() {
        val inputs = (1..10).map { PatchInput("m/src/$it.kt", ChangeStatus.MODIFIED,
            "@@ -1 +1 @@\n-old\n+" + "\"\\\t".repeat(500) + "\n") }
        val diff = StructuredDiffBuilder(DiffLimits(maxChars = 3000)).buildFromPatch(inputs, 10, 10)
        assertTrue(ObjectMapper().writeValueAsString(diff).length <= 3000)
        assertEquals(10, diff.files.size)
        assertEquals(10, diff.totalFiles)
        assertTrue(diff.truncated)
        assertTrue(diff.omissions.any { it.reason == "maxChars" })
        assertTrue(diff.keyHunks.isEmpty(), "Oversized hunks must be omitted intact")
    }

    @Test
    fun `summary overflow preserves original counts and records omissions`() {
        val diff = StructuredDiffBuilder(DiffLimits(maxChars = 700)).buildFromPatch(
            (1..50).map { PatchInput("long-name-$it.kt", ChangeStatus.ADDED, "@@ -0,0 +1 @@\n+x\n") }, 50, 1)
        assertTrue(ObjectMapper().writeValueAsString(diff).length <= 700)
        assertEquals(50, diff.totalFiles)
        assertEquals(50, diff.totalAdditions)
        assertTrue(diff.files.size < 50)
        assertTrue(diff.omissions.any { it.reason == "maxChars" && it.files > 0 })
    }

    @Test
    fun `counts come from hunk bodies including lines resembling file headers`() {
        val diff = StructuredDiffBuilder().buildFromPatch(listOf(PatchInput("src/main/kotlin/com/example/aicommit/service/A.kt",
            ChangeStatus.MODIFIED, "--- a/A.kt\n+++ b/A.kt\n@@ -1,2 +1,2 @@\n---old\n+++new\n unchanged\n")), 10, 10)
        assertEquals(1, diff.totalAdditions)
        assertEquals(1, diff.totalDeletions)
        assertNull(diff.files.single().module)
    }

    @Test
    fun `mode-only change retains evidence in model input`() {
        val diff = StructuredDiffBuilder().buildFromPatch(listOf(PatchInput("run.sh", ChangeStatus.MODIFIED,
            "diff --git a/run.sh b/run.sh\nold mode 100644\nnew mode 100755\n")), 10, 10)
        assertEquals("100644", diff.files.single().oldMode)
        assertEquals("100755", diff.files.single().newMode)
        assertTrue(diff.keyHunks.isEmpty())
        assertTrue(ObjectMapper().writeValueAsString(diff).contains("100755"))
    }

    @Test
    fun `sensitive old rename paths and binary inputs are recorded without content`() {
        val diff = StructuredDiffBuilder().buildFromPatch(listOf(
            PatchInput("safe.txt", ChangeStatus.RENAMED, "SECRET", oldPath = ".env"),
            PatchInput("image.png", ChangeStatus.MODIFIED, "SECRET", skipReason = "binary"),
            PatchInput("ok.kt", ChangeStatus.MODIFIED, "@@ -1 +1 @@\n-a\n+b\n")
        ), 10, 10)
        assertEquals(3, diff.totalFiles)
        assertFalse(ObjectMapper().writeValueAsString(diff).contains("SECRET"))
        assertEquals(setOf("sensitive", "binary"), diff.omissions.map { it.reason }.toSet())
        assertEquals(listOf("ok.kt"), diff.files.map { it.relativePath })
    }
}
