package com.appblocker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Debug-only file logger (easy to remove).
object DebugLogStore {
    private const val LOG_FILE_NAME = "overlay-debug.log"
    private const val MAX_BYTES = 512 * 1024
    private val lock = Any()

    data class ExportResult(
        val uri: Uri?,
        val displayName: String,
        val path: String?
    )

    fun append(context: Context, tag: String, message: String) {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$timestamp [$tag] $message"
        synchronized(lock) {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                file.delete()
            }
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 8 * 1024)
                .use { writer ->
                    writer.appendLine(line)
                }
        }
    }

    fun export(context: Context): ExportResult? {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return null
        synchronized(lock) {
            val source = File(context.filesDir, LOG_FILE_NAME)
            if (!source.exists()) return null

            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val displayName = "overlay-debug-$timestamp.log"

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                ExportResult(uri, displayName, null)
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: context.getExternalFilesDir(null)
                    ?: return null
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val dest = File(dir, displayName)
                source.copyTo(dest, overwrite = true)
                ExportResult(null, displayName, dest.absolutePath)
            }
        }
    }

    fun exportForShare(context: Context): Uri? {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return null
        val result = export(context) ?: return null
        if (result.uri != null) return result.uri
        val path = result.path ?: return null
        val file = File(path)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}
