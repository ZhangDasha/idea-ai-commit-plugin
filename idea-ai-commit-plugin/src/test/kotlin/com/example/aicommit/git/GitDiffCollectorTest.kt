package com.example.aicommit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
