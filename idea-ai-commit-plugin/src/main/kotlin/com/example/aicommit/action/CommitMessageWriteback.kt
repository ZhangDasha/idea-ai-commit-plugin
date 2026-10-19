package com.example.aicommit.action

/** Accessed only on the UI thread. Read the raw document so whitespace edits remain observable. */
internal class CommitMessageWriteback(
    private val read: () -> String,
    private val write: (String) -> Unit
) {
    private val original = read()
    private var lastWritten = original
    private var userEdited = false

    fun update(next: String) {
        if (read() != lastWritten) userEdited = true
        if (userEdited) return
        write(next)
        // The editor may normalize line separators. Compare against what it actually stored.
        lastWritten = read()
    }

    fun restore() = update(original)
}
