package com.drasite.mercedesobd

import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Only completed, user-selected logs are handed to this store by the activity. */
internal class LogFiles(private val root: File, private val exports: File) {
    fun list(): List<File> = root.listFiles()?.filter {
        it.isFile && it.extension in listOf("csv", "jsonl") && it.canonicalFile.parentFile == root.canonicalFile
    }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun selected(names: List<String>): List<File> {
        require(names.isNotEmpty()) { tr("Selecciona algún registro", "Select at least one log") }
        return names.distinct().map { name ->
            val file = File(root, name).canonicalFile
            require(file.parentFile == root.canonicalFile && name == file.name && file.extension in listOf("csv", "jsonl"))
            if (!file.isFile) throw FileNotFoundException(name)
            file
        }
    }

    fun delete(names: List<String>): List<String> = selected(names).filterNot { it.delete() }.map { it.name }

    fun prepare(names: List<String>): File {
        val files = selected(names)
        val directory = File(exports, UUID.randomUUID().toString())
        check(directory.mkdirs()) { tr("No se pudo preparar la exportación", "Could not prepare export") }
        if (files.size == 1) return files.single().copyTo(File(directory, files.single().name))
        val zip = File(directory, "me21_logs.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            files.forEach { file ->
                out.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    companion object {
        fun mime(file: File) = when (file.extension) {
            "csv" -> "text/csv"
            "jsonl" -> "application/x-ndjson"
            else -> "application/zip"
        }

        fun exportedFile(root: File, parts: List<String>): File {
            if (parts.size != 2 || parts.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\\' in it })
                throw FileNotFoundException("Invalid export")
            val file = File(File(root, parts[0]), parts[1]).canonicalFile
            if (file.parentFile?.parentFile != root.canonicalFile || !file.isFile)
                throw FileNotFoundException("Export unavailable")
            return file
        }
    }
}
