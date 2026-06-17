package com.example.aicommit.git

import com.example.aicommit.model.ChangeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredDiffBuilderTest {

    @Test
    fun `build should skip sensitive files and keep module summaries`() {
        val builder = StructuredDiffBuilder(DiffLimits(maxChars = 1_000))

        val diff = builder.buildFromPatch(
            listOf(
                PatchInput(
                    "src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt",
                    ChangeStatus.MODIFIED,
                    "@@ -1 +1 @@\n-old\n+new"
                ),
                PatchInput(".env", ChangeStatus.MODIFIED, "@@ -1 +1 @@\n-OLD=1\n+OLD=2")
            ),
            maxFiles = 10,
            maxHunksPerFile = 2
        )

        // .env 被过滤，仅剩 1 个文件
        assertEquals(1, diff.files.size)
        assertEquals(
            "src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt",
            diff.files.first().relativePath
        )
        // 模块推断为 service（aicommit 下一级目录）
        assertEquals("service", diff.files.first().module)
        assertFalse(diff.truncated)
        // 关键 hunk 保留了新增行
        assertTrue(diff.keyHunks.first().patch.contains("+new"))
    }

    @Test
    fun `build should mark truncated when files exceed maxFiles`() {
        val builder = StructuredDiffBuilder(DiffLimits(maxChars = 1_000))

        val diff = builder.buildFromPatch(
            listOf(
                PatchInput("a.kt", ChangeStatus.MODIFIED, "@@ -1 +1 @@\n-a\n+a"),
                PatchInput("b.kt", ChangeStatus.MODIFIED, "@@ -1 +1 @@\n-b\n+b"),
                PatchInput("c.kt", ChangeStatus.MODIFIED, "@@ -1 +1 @@\n-c\n+c")
            ),
            maxFiles = 2,
            maxHunksPerFile = 1
        )

        assertEquals(2, diff.files.size)
        assertTrue(diff.truncated)
    }

    @Test
    fun `build should cap hunks per file`() {
        val builder = StructuredDiffBuilder(DiffLimits(maxChars = 5_000))

        val diff = builder.buildFromPatch(
            listOf(
                PatchInput(
                    "a.kt",
                    ChangeStatus.MODIFIED,
                    "@@ -1 +1 @@\n-a\n+a\n@@ -10 +10 @@\n-x\n+x\n@@ -20 +20 @@\n-y\n+y"
                )
            ),
            maxFiles = 10,
            maxHunksPerFile = 2
        )

        // 每文件最多 2 个 hunk
        assertEquals(2, diff.keyHunks.size)
    }
}
