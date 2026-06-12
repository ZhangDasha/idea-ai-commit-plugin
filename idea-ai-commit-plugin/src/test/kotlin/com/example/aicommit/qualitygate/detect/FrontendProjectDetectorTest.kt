package com.example.aicommit.qualitygate.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrontendProjectDetectorTest {

    @Test
    fun `detectProjects should return frontend project roots only`() {
        val detector = FrontendProjectDetector()
        val paths = listOf(
            "/repo/web/src/App.vue",
            "/repo/server/src/main/java/App.java"
        )
        // 模拟文件存在性：/repo/web 下有 package.json 和 tsconfig.json
        val result = detector.detectProjectRoots(paths) { candidate ->
            candidate == "/repo/web" ||
                candidate == "/repo/web/package.json" ||
                candidate == "/repo/web/tsconfig.json"
        }
        assertEquals(listOf("/repo/web"), result)
    }

    @Test
    fun `detectProjects should return empty when no frontend files`() {
        val detector = FrontendProjectDetector()
        val paths = listOf(
            "/repo/server/src/main.java",
            "/repo/server/README.md"
        )
        val result = detector.detectProjectRoots(paths) { false }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectProjects should return empty when no packageJson`() {
        val detector = FrontendProjectDetector()
        val paths = listOf("/repo/web/src/App.tsx")
        // 没有 package.json
        val result = detector.detectProjectRoots(paths) { candidate ->
            candidate == "/repo/web/tsconfig.json"
        }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectProjects should deduplicate multiple files in same project`() {
        val detector = FrontendProjectDetector()
        val paths = listOf(
            "/repo/web/src/App.tsx",
            "/repo/web/src/utils.ts",
            "/repo/web/src/style.css"
        )
        val result = detector.detectProjectRoots(paths) { candidate ->
            candidate == "/repo/web" ||
                candidate == "/repo/web/package.json" ||
                candidate == "/repo/web/tsconfig.json"
        }
        assertEquals(1, result.size)
        assertEquals("/repo/web", result.first())
    }

    @Test
    fun `detectProjects should detect multiple frontend projects`() {
        val detector = FrontendProjectDetector()
        val paths = listOf(
            "/repo/web-pc/src/App.vue",
            "/repo/web-h5/src/main.tsx"
        )
        val existingFiles = setOf(
            "/repo/web-pc", "/repo/web-pc/package.json", "/repo/web-pc/tsconfig.json",
            "/repo/web-h5", "/repo/web-h5/package.json", "/repo/web-h5/eslint.config.js"
        )
        val result = detector.detectProjectRoots(paths) { existingFiles.contains(it) }
        assertEquals(2, result.size)
        assertTrue(result.contains("/repo/web-pc"))
        assertTrue(result.contains("/repo/web-h5"))
    }
}
