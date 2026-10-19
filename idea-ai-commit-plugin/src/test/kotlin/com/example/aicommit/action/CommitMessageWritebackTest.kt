package com.example.aicommit.action

import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageWritebackTest {
    @Test fun `failure or cancel restores original after streamed text`() {
        var text = "原有提交信息  \n"
        val writeback = CommitMessageWriteback({ text }) { text = it }
        writeback.update("generating")
        writeback.update("fix: 部分输出 \n")
        writeback.restore()
        assertEquals("原有提交信息  \n", text)
    }

    @Test fun `stream final result and restore preserve user edits`() {
        var text = "original"
        val writeback = CommitMessageWriteback({ text }) { text = it }
        writeback.update("generating")
        text = "用户手动输入"
        writeback.update("streamed")
        writeback.update("final")
        writeback.restore()
        assertEquals("用户手动输入", text)
    }

    @Test fun `final validated output replaces streamed draft`() {
        var text = ""
        val writeback = CommitMessageWriteback({ text }) { text = it }
        writeback.update("fix: draft")
        writeback.update("fix: 修复取消后无法再次生成")
        assertEquals("fix: 修复取消后无法再次生成", text)
    }

    @Test fun `whitespace chunks and normalized line endings do not stop writeback`() {
        var document = "original"
        // CommitMessage exposes a trimmed comment, but the action reads its raw document.
        val writeback = CommitMessageWriteback({ document }) { document = it.replace("\r\n", "\n") }
        listOf("fix:", "fix: ", "fix: restore streaming\r\n", "fix: restore streaming\r\n\r\nBody ").forEach {
            writeback.update(it)
            assertEquals(it.replace("\r\n", "\n"), document)
        }
        writeback.update("fix: restore streaming")
        assertEquals("fix: restore streaming", document)
        writeback.restore()
        assertEquals("original", document)
    }

    @Test fun `user whitespace edit is preserved`() {
        var document = ""
        val writeback = CommitMessageWriteback({ document }) { document = it }
        writeback.update("fix: draft")
        document += " "
        writeback.update("fix: final")
        writeback.restore()
        assertEquals("fix: draft ", document)
    }
}
