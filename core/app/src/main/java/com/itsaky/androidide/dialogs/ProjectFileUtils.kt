package com.itsaky.androidide.dialogs // Your current package for this interface

import android.util.Log
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.nio.file.InvalidPathException
import java.util.Locale // Added for lowercase

object ProjectFileUtils {

    private const val TAG = "ProjectFileUtils"

    fun scanProjectFiles(projectRoot: File): List<String> {
        val result = mutableListOf<String>()
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            Log.w(TAG, "scanProjectFiles: Project root directory does not exist or is not a directory: ${projectRoot.absolutePath}")
            return result
        }
        collectFilePaths(projectRoot, projectRoot, result)
        return result
    }

    private fun collectFilePaths(baseDir: File, currentDir: File, result: MutableList<String>) {
        val files = currentDir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                if (file.name in arrayOf("build", ".gradle", ".git", ".idea", "generated", "libs", "obj", "bin")) { // Added more common ignores
                    continue
                }
                collectFilePaths(baseDir, file, result)
            } else {
                if (isCodeFile(file.name)) {
                    val relativePath = file.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    result.add(relativePath)
                }
            }
        }
    }

    private fun isCodeFile(filename: String): Boolean {
        val codeExtensions = arrayOf(".kt", ".java", ".xml", ".gradle", ".kts", ".properties", ".json", ".md") // Added .kts
        return codeExtensions.any { filename.lowercase(Locale.ROOT).endsWith(it) }
    }

    /**
     * Resolves an AI-provided relative path inside [projectDir].
     *
     * This is intentionally fail-safe: absolute paths, blank paths, Windows drive paths, and
     * traversal attempts such as `../settings.gradle.kts` are rejected before any write/delete is
     * attempted. The method does not require the destination file to already exist.
     */
    fun safeResolveProjectPath(projectDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        return try {
            val sanitizedPath = relativePath.trim()
                .replace('\\', '/')
                .removePrefix("./")
            if (sanitizedPath.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(sanitizedPath)) {
                Log.w(TAG, "Rejected unsafe absolute path from AI response: $relativePath")
                return null
            }

            val rootPath = projectDir.toPath().toAbsolutePath().normalize()
            val resolvedPath = rootPath.resolve(sanitizedPath).normalize()
            if (!resolvedPath.startsWith(rootPath)) {
                Log.w(TAG, "Rejected path traversal from AI response: $relativePath")
                null
            } else {
                resolvedPath.toFile()
            }
        } catch (e: InvalidPathException) {
            Log.w(TAG, "Rejected invalid path from AI response: $relativePath", e)
            null
        }
    }

    fun processFileChangesAndDeletions(
        projectDir: File,
        filesToWrite: Map<String, String>,
        filesToDelete: List<String>,
        logAppender: (String) -> Unit,
        onComplete: (writeSuccessCount: Int, writeErrorCount: Int, deleteSuccessCount: Int, deleteErrorCount: Int) -> Unit
    ) {
        var writeSuccess = 0
        var writeError = 0
        var deleteSuccess = 0
        var deleteError = 0

        logAppender("--- Starting File System Operations ---\n")

        if (filesToDelete.isNotEmpty()) {
            logAppender("Deletion Phase: Attempting to delete ${filesToDelete.size} file(s).\n")
            filesToDelete.forEach { relativePath ->
                val file = safeResolveProjectPath(projectDir, relativePath)
                if (file == null) {
                    logAppender("⚠️ Unsafe or blank delete path rejected: $relativePath\n")
                    deleteError++
                    return@forEach
                }
                if (file.exists()) {
                    try {
                        if (file.isFile) {
                            if (file.delete()) {
                                logAppender("✅ Deleted file: $relativePath\n")
                                deleteSuccess++
                            } else {
                                logAppender("⚠️ Failed to delete file (unknown reason): $relativePath\n")
                                deleteError++
                            }
                        } else if (file.isDirectory) { // Simple directory deletion (non-recursive)
                            if (file.listFiles()?.isEmpty() == true && file.delete()) {
                                logAppender("✅ Deleted empty directory: $relativePath\n")
                                deleteSuccess++
                            } else {
                                logAppender("⚠️ Path for deletion is a non-empty directory or failed to delete: $relativePath. Manual deletion might be required.\n")
                                deleteError++
                            }
                        }
                    } catch (e: SecurityException) {
                        logAppender("⚠️ Security error deleting $relativePath: ${e.message}\n")
                        deleteError++
                    } catch (e: Exception) {
                        logAppender("⚠️ Unexpected error deleting $relativePath: ${e.message}\n")
                        deleteError++
                    }
                } else {
                    logAppender("ℹ️ File for deletion not found: $relativePath\n")
                }
            }
            logAppender("Deletion Phase complete. Success: $deleteSuccess, Errors: $deleteError.\n")
        } else {
            logAppender("Deletion Phase: No files specified for deletion.\n")
        }

        if (filesToWrite.isNotEmpty()) {
            logAppender("Writing Phase: Attempting to write/update ${filesToWrite.size} file(s).\n")
            filesToWrite.forEach { (relativePath, content) ->
                val file = safeResolveProjectPath(projectDir, relativePath)
                if (file == null) {
                    logAppender("⚠️ Unsafe or blank write path rejected: $relativePath\n")
                    writeError++
                    return@forEach
                }
                try {
                    file.parentFile?.mkdirs()
                    FileWriter(file).use { it.write(content) }
                    logAppender("✅ Updated/Created file: $relativePath\n")
                    writeSuccess++
                } catch (e: IOException) {
                    logAppender("❌ IO Failed to write $relativePath: ${e.message}\n")
                    writeError++
                } catch (e: SecurityException) {
                    logAppender("❌ Security exception writing $relativePath: ${e.message}\n")
                    writeError++
                } catch (e: Exception) {
                    logAppender("❌ Failed to write $relativePath (Unexpected Error): ${e.message}\n")
                    writeError++
                }
            }
            logAppender("Writing Phase complete. Success: $writeSuccess, Errors: $writeError.\n")
        } else {
            logAppender("Writing Phase: No files specified for writing/updating.\n")
        }

        logAppender("--- File System Operations Complete ---\n")
        onComplete(writeSuccess, writeError, deleteSuccess, deleteError)
    }

    // Helper to find a common package name
    fun findCommonPackageName(projectDir: File?, appName: String, codeFileContents: Collection<String>): String {
        // 1. Try from AndroidManifest.xml
        projectDir?.let {
            val manifestFile = File(it, "app/src/main/AndroidManifest.xml")
            if (manifestFile.exists()) {
                try {
                    val manifestContent = FileReader(manifestFile).use { reader -> reader.readText() }
                    Regex("""package="([^"]+)"""").find(manifestContent)?.groupValues?.get(1)?.let { packageName ->
                        if (packageName.isNotBlank()) return packageName
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Could not read AndroidManifest.xml to find package name", e)
                }
            }
        }

        // 2. Try from existing code files
        for (content in codeFileContents) {
            Regex("""^\s*package\s+([a-zA-Z0-9_.]+)""").find(content)?.groupValues?.get(1)?.let { packageName ->
                if (packageName.isNotBlank() && !packageName.startsWith("java.") && !packageName.startsWith("javax.") && !packageName.startsWith("android.")) {
                    return packageName
                }
            }
        }

        // 3. Generate a default based on appName
        val sanitizedAppName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault()).ifEmpty { "myapp" }
        return "com.example.$sanitizedAppName"
    }
}
