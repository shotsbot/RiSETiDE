package com.itsaky.androidide.dialogs

import android.util.Log

/**
 * Converts LLM markdown responses into concrete file writes.
 *
 * Supported formats:
 * ```
 * File: app/src/main/java/com/example/MainActivity.kt
 * ```kotlin
 * ...full file...
 * ```
 *
 * ```kotlin file=app/src/main/AndroidManifest.xml
 * ...full file...
 * ```
 *
 * ```
 * // File: settings.gradle.kts
 * ...full file...
 * ```
 */
object AiMarkdownCodeBlockParser {
    private const val TAG = "AiMarkdownCodeBlockParser"

    private val fencedBlockRegex = Regex("""(?s)```([^\n`]*)\n(.*?)\n```""")
    private val labelledBlockRegex = Regex(
        """(?im)^\s*(?:file|path)\s*:\s*`?([^`\n]+?)`?\s*$\s*```[^\n`]*\n(.*?)\n```""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val inlinePathRegex = Regex("""(?:file|path)\s*=\s*['\"]?([^'\"\s]+)['\"]?""", RegexOption.IGNORE_CASE)
    private val commentPathRegex = Regex("""(?m)^\s*(?://|#|<!--)\s*(?:File|Path)\s*:\s*([^\n>]+?)\s*(?:-->)?\s*$""")

    fun parseFileWrites(markdown: String): Map<String, String> {
        if (markdown.isBlank()) return emptyMap()
        val writes = linkedMapOf<String, String>()

        labelledBlockRegex.findAll(markdown).forEach { match ->
            val path = normalizePath(match.groupValues[1])
            val content = match.groupValues[2]
            if (path != null) writes[path] = stripLeadingPathComment(content, path)
        }

        fencedBlockRegex.findAll(markdown).forEach { match ->
            val info = match.groupValues[1]
            val content = match.groupValues[2]
            val path = extractPathFromFenceInfo(info) ?: extractPathFromContentHeader(content)
            if (path != null && path !in writes) {
                writes[path] = stripLeadingPathComment(content, path)
            }
        }

        Log.d(TAG, "parseFileWrites: extracted ${writes.size} markdown file block(s)")
        return writes
    }

    private fun extractPathFromFenceInfo(info: String): String? {
        val trimmed = info.trim()
        inlinePathRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { raw ->
            return normalizePath(raw)
        }
        return normalizePath(trimmed).takeIf { candidate ->
            candidate != null && (candidate.contains('/') || candidate.contains('.'))
        }
    }

    private fun extractPathFromContentHeader(content: String): String? {
        val firstLines = content.lineSequence().take(3).joinToString("\n")
        return commentPathRegex.find(firstLines)?.groupValues?.getOrNull(1)?.let(::normalizePath)
    }

    private fun normalizePath(rawPath: String): String? {
        val path = rawPath.trim()
            .removeSurrounding("`")
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace('\\', '/')
            .removePrefix("./")
            .trim()
        if (path.isBlank() || path.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(path)) return null
        if (path.split('/').any { it == ".." }) return null
        return path
    }

    private fun stripLeadingPathComment(content: String, path: String): String {
        val lines = content.lines().toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        val first = lines.firstOrNull() ?: return content
        if (commentPathRegex.matches(first) && first.contains(path)) {
            lines.removeAt(0)
            if (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
            return lines.joinToString("\n")
        }
        return content
    }
}
