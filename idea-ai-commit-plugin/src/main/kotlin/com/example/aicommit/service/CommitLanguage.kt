package com.example.aicommit.service

/** A lightweight language guard for prose, excluding technical identifiers and quoted literals. */
internal object CommitLanguage {
    private val quoted = Regex("`[^`]*`|\"[^\"]*\"|'[^']*'")

    fun isChinese(language: String): Boolean = when (language.trim().lowercase()) {
        "chinese", "中文", "简体中文", "zh", "zh-cn", "zh-hans" -> true
        "english", "英文", "en", "en-us", "en-gb" -> false
        else -> throw IllegalArgumentException("仅支持 Chinese 或 English 输出语言")
    }

    fun instruction(language: String): String = if (isChinese(language)) {
        "输出语言必须为简体中文。自然语言的 intent、why、impact、subject、body 和破坏性变更说明必须用中文；" +
            "type、scope、JSON 字段名、BREAKING CHANGE 标签和技术标识符保留原文。" +
            "不要把英文意图直接复制到提交标题。此语言要求独立于用户模板并优先于模板中的语言描述。"
    } else {
        "Output all natural-language intent, why, impact, subject, body and breaking-change descriptions in English. " +
            "Keep Conventional Commit types, JSON keys, scopes, technical identifiers and quoted source literals unchanged. " +
            "This language requirement overrides conflicting language instructions in the user template."
    }

    fun validate(text: String, language: String, field: String) {
        val prose = quoted.replace(text, "")
        val hasHan = prose.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
        if (isChinese(language)) {
            require(hasHan) { "$field 必须使用中文；英文 type、scope 和技术标识符可以保留" }
        } else {
            require(!hasHan && prose.any { it in 'a'..'z' || it in 'A'..'Z' }) { "$field 必须使用英文" }
        }
    }
}
