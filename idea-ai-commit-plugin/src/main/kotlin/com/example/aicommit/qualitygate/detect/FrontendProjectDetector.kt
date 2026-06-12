package com.example.aicommit.qualitygate.detect

import java.io.File

/**
 * 前端项目识别器：从变更文件路径反推前端项目根目录
 *
 * 规则：
 * 1. 文件扩展名属于前端类型 (.js/.jsx/.ts/.tsx/.vue)
 * 2. 向上查找包含 package.json 的目录
 * 3. 该目录同时存在 tsconfig 或 eslint 配置文件
 */
class FrontendProjectDetector {

    private val frontendExtensions = setOf(".js", ".jsx", ".ts", ".tsx", ".vue")

    // eslint 配置文件名（含新式 flat config 和旧式 rc）
    private val configNames = setOf(
        "tsconfig.json",
        "eslint.config.js", "eslint.config.cjs", "eslint.config.mjs",
        ".eslintrc", ".eslintrc.js", ".eslintrc.cjs",
        ".eslintrc.json", ".eslintrc.yaml", ".eslintrc.yml"
    )

    /**
     * 从变更文件路径中识别前端项目根目录
     * @param paths 变更文件路径列表
     * @param exists 文件存在性检查（默认使用 File.exists，可注入用于测试）
     * @return 去重后的项目根目录列表
     */
    fun detectProjectRoots(
        paths: List<String>,
        exists: (String) -> Boolean = { File(it).exists() }
    ): List<String> {
        return paths.asSequence()
            .filter { path -> frontendExtensions.any { ext -> path.endsWith(ext, ignoreCase = true) } }
            .mapNotNull { locateProjectRoot(it, exists) }
            .distinct()
            .toList()
    }

    /** 从文件路径向上查找包含 package.json 且有前端配置的项目根 */
    private fun locateProjectRoot(filePath: String, exists: (String) -> Boolean): String? {
        var current: File? = File(filePath).parentFile
        while (current != null) {
            val packageJson = File(current, "package.json").path
            if (exists(packageJson) && hasFrontendConfig(current, exists)) {
                return current.path
            }
            current = current.parentFile
        }
        return null
    }

    /** 检查目录下是否存在 tsconfig 或 eslint 配置 */
    private fun hasFrontendConfig(root: File, exists: (String) -> Boolean): Boolean {
        // tsconfig.json 或 tsconfig.*.json
        if (exists(File(root, "tsconfig.json").path)) return true
        if (root.listFiles()?.any { it.name.startsWith("tsconfig.") && it.name.endsWith(".json") } == true) return true
        // eslint 配置文件
        return configNames.any { exists(File(root, it).path) }
    }
}
