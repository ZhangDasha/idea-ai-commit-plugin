package com.example.aicommit.action

/** Accessed only on the UI thread. User edits take precedence over generated updates. */
internal class CommitMessageWriteback(private val original: String) {
    private var lastWritten = normalize(original)
    private var userEdited = false

    fun update(current: String, next: String, write: (String) -> Unit) {
        if (normalize(current) != lastWritten) userEdited = true
        if (userEdited) return
        write(next)
        lastWritten = normalize(next)
    }

    fun restore(current: String, write: (String) -> Unit) = update(current, original, write)

    private fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}
