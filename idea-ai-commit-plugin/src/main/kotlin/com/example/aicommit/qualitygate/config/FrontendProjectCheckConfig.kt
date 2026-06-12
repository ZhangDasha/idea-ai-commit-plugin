package com.example.aicommit.qualitygate.config

/**
 * 前端项目级检查配置，以项目根目录为唯一 key
 */
data class FrontendProjectCheckConfig(
    var projectRoot: String = "",
    var typescriptCommand: String? = null,
    var eslintCommand: String? = null,
    var customCommands: MutableList<String> = mutableListOf()
)
