package com.example.aicommit.action

import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageWritebackTest {
    @Test fun `failure or cancel restores original after streamed text`() {
        var text = "原有提交信息"
        val writeback = CommitMessageWriteback(text)
        writeback.update(text, "generating") { text = it }
        writeback.update(text, "fix: 部分输出") { text = it }
        writeback.restore(text) { text = it }
        assertEquals("原有提交信息", text)
    }

    @Test fun `stream final result and restore preserve user edits`() {
        var text = "original"
        val writeback = CommitMessageWriteback(text)
        writeback.update(text, "generating") { text = it }
        text = "用户手动输入"
        writeback.update(text, "streamed") { text = it }
        writeback.update(text, "final") { text = it }
        writeback.restore(text) { text = it }
        assertEquals("用户手动输入", text)
    }

    @Test
    fun `raw streamed whitespace does not prevent final writeback`() {
        var document = ""
        val writeback = CommitMessageWriteback(document)
        writeback.update(document, "feat(ReadSitemapService): ") { document = it }
        // CommitMessage.text would trim this space; Action must compare document.text.
        assertEquals("feat(ReadSitemapService):", document.trimEnd())
        writeback.update(document, "feat(ReadSitemapService): remove markdown parameter\n") { document = it }
        writeback.update(document, "feat(ReadSitemapService): 移除 markdown 参数") { document = it }
        assertEquals("feat(ReadSitemapService): 移除 markdown 参数", document)
    }

    @Test
    fun `line separator normalization does not look like a user edit`() {
        var document = ""
        val writeback = CommitMessageWriteback(document)
        writeback.update(document, "draft\r\n") { document = it.replace("\r\n", "\n") }
        writeback.update(document, "fix: 完成生成") { document = it }
        assertEquals("fix: 完成生成", document)
    }

    @Test fun `final validated output replaces streamed draft`() {
        var text = ""
        val writeback = CommitMessageWriteback(text)
        writeback.update(text, "fix: draft") { text = it }
        writeback.update(text, "fix: 修复取消后无法再次生成") { text = it }
        assertEquals("fix: 修复取消后无法再次生成", text)
    }
}
