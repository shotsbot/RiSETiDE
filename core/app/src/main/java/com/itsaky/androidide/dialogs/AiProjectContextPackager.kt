package com.itsaky.androidide.dialogs

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale

/** Builds bounded, long-context-friendly project snapshots for cloud/local LLM calls. */
object AiProjectContextPackager {
    private const val TAG = "AiProjectContextPackager"
    private const val DEFAULT_MAX_CHARS_PER_FILE = 48_000
    private const val DEFAULT_MAX_TOTAL_CHARS = 220_000

    private val ignoredDirectories = setOf(
        ".git", ".gradle", ".idea", "build", "generated", "captures", "node_modules", "obj", "bin"
    )
    private val codeExtensions = setOf("kt", "java", "xml", "gradle", "kts", "json", "properties", "toml", "md", "pro")

    data class FileChunk(
        val relativePath: String,
        val index: Int,
        val total: Int,
        val content: String,
        val truncated: Boolean
    )

    data class Snapshot(
        val projectTree: String,
        val chunks: List<FileChunk>,
        val totalChars: Int,
        val truncated: Boolean
    )

    fun buildSnapshot(
        projectDir: File,
        maxCharsPerFile: Int = DEFAULT_MAX_CHARS_PER_FILE,
        maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS
    ): Snapshot {
        if (!projectDir.isDirectory) return Snapshot("", emptyList(), 0, truncated = true)

        val files = projectDir.walkTopDown()
            .onEnter { dir -> dir == projectDir || dir.name !in ignoredDirectories }
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in codeExtensions }
            .sortedBy { it.relativeTo(projectDir).path }
            .toList()

        val tree = files.joinToString(separator = "\n") { "- ${it.relativeTo(projectDir).path.replace(File.separatorChar, '/')}" }
        val chunks = mutableListOf<FileChunk>()
        var totalChars = 0
        var truncated = false

        for (file in files) {
            val relativePath = file.relativeTo(projectDir).path.replace(File.separatorChar, '/')
            val raw = try {
                file.readText()
            } catch (e: IOException) {
                Log.w(TAG, "Skipping unreadable file: $relativePath", e)
                continue
            }
            val bounded = if (raw.length > maxCharsPerFile) {
                truncated = true
                raw.take(maxCharsPerFile) + "\n/* AI_CONTEXT_TRUNCATED: ${raw.length - maxCharsPerFile} chars omitted */"
            } else raw

            val parts = bounded.chunked(maxCharsPerFile)
            for ((index, part) in parts.withIndex()) {
                if (totalChars + part.length > maxTotalChars) {
                    truncated = true
                    return Snapshot(tree, chunks, totalChars, truncated = true)
                }
                chunks += FileChunk(relativePath, index + 1, parts.size, part, raw.length > maxCharsPerFile)
                totalChars += part.length
            }
        }
        return Snapshot(tree, chunks, totalChars, truncated)
    }

    fun extractBuildFailure(logText: String, maxChars: Int = 32_000): String {
        if (logText.isBlank()) return ""
        val markers = listOf("FAILURE: Build failed", "* What went wrong:", "Exception is:", "Caused by:", "error:")
        val firstMarker = markers.mapNotNull { marker -> logText.indexOf(marker).takeIf { it >= 0 } }.minOrNull() ?: 0
        return logText.substring(firstMarker).take(maxChars)
    }
}
