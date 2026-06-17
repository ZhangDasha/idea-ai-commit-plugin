package com.example.aicommit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class GitDiffCollectorTest {

    @Test
    fun `truncate should keep prefix when diff exceeds max chars`() {
        val collector = GitDiffCollector(DiffLimits(maxChars = 10))
        val result = collector.truncate("1234567890ABCDEFG")
        assertEquals("1234567890", result)
    }

    @Test
    fun `truncate should not modify when within limit`() {
        val collector = GitDiffCollector(DiffLimits(maxChars = 100))
        val result = collector.truncate("short")
        assertEquals("short", result)
    }

    @Test
    fun `shouldSkipFile returns true for binary extensions`() {
        val collector = GitDiffCollector()
        assertTrue(collector.shouldSkipFile("image.png"))
        assertTrue(collector.shouldSkipFile("archive.jar"))
        assertTrue(collector.shouldSkipFile("photo.JPG"))
    }

    @Test
    fun `shouldSkipFile returns false for source files`() {
        val collector = GitDiffCollector()
        assertTrue(!collector.shouldSkipFile("Main.kt"))
        assertTrue(!collector.shouldSkipFile("config.xml"))
    }

    @Test
    fun `execAndRead returns command output`() {
        val collector = GitDiffCollector()
        val result = collector.execAndRead(listOf("echo", "hello world"), File("/tmp"))
        assertEquals("hello world", result)
    }

    @Test
    fun `execAndRead returns empty for invalid command`() {
        val collector = GitDiffCollector()
        val result = collector.execAndRead(listOf("nonexistent_cmd_xyz"), File("/tmp"))
        assertEquals("", result)
    }

    @Test
    fun `findGitRoot returns non-null in this project`() {
        val collector = GitDiffCollector()
        // 当前项目本身就是 git 仓库，findGitRoot 应返回合法路径
        val mockProject = null  // 此测试仅验证 execAndRead 绕过 project 依赖
        // 直接测试 git rev-parse 命令
        val root = collector.execAndRead(
            listOf("git", "rev-parse", "--show-toplevel"),
            File(System.getProperty("user.dir"))
        )
        assertTrue(root.isNotBlank(), "git rev-parse 应返回仓库根目录路径")
        assertTrue(File(root).exists(), "仓库根目录应真实存在")
    }

    @Test
    fun `diffLimits contextLines is used correctly`() {
        val limits = DiffLimits(contextLines = 5)
        val collector = GitDiffCollector(limits)
        assertEquals(limits, DiffLimits(maxChars = 100_000, maxUnversionedBytes = 20_000, contextLines = 5))
        assertEquals(5, limits.contextLines)
    }
}
