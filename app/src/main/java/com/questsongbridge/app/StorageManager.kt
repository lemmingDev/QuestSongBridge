package com.questsongbridge.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

object StorageManager {
    fun hasSelection(): Boolean = Settings.getDestUri() != null

    fun getTreeUri(): Uri? = Settings.getDestUri()?.let { Uri.parse(it) }

    fun persistPermission(ctx: Context, uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            // ignore if not persistable
        }
        Settings.setDestUri(uri.toString())
    }

    fun revoke() {
        val uri = getTreeUri()
        if (uri != null) {
            try {
                AppContext.context.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) { }
        }
        Settings.setDestUri(null)
    }

    fun getDocumentFile(ctx: Context): DocumentFile? {
        val uri = getTreeUri() ?: return null
        return DocumentFile.fromTreeUri(ctx, uri)
    }

    fun isWritable(ctx: Context): Boolean {
        val df = getDocumentFile(ctx) ?: return false
        if (!df.canWrite()) return false
        // test write
        return testWrite(ctx)
    }

    fun testWrite(ctx: Context): Boolean {
        val df = getDocumentFile(ctx) ?: return false
        var f: DocumentFile? = null
        return try {
            f = df.createFile("text/plain", "__qs_test__.tmp")
            if (f == null) return false
            ctx.contentResolver.openOutputStream(f.uri, "w")?.use { it.write("test".toByteArray()) } ?: return false
            true
        } catch (e: Exception) {
            false
        } finally {
            try { f?.delete() } catch (e: Exception) { }
        }
    }

    fun displayName(ctx: Context): String? {
        val df = getDocumentFile(ctx) ?: return null
        return df.name ?: getTreeUri()?.lastPathSegment
    }

    fun ensureDir(ctx: Context, parent: DocumentFile, name: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        return parent.createDirectory(name)
    }

    fun createFile(ctx: Context, parent: DocumentFile, name: String, mime: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null && existing.isFile) return existing
        return parent.createFile(mime, name)
    }

    fun openOutputStream(ctx: Context, uri: Uri): OutputStream? {
        return ctx.contentResolver.openOutputStream(uri, "w")
    }

    fun deleteRecursive(ctx: Context, df: DocumentFile): Boolean {
        return try { df.delete() } catch (e: Exception) { false }
    }

    fun exists(ctx: Context, name: String): Boolean {
        val root = getDocumentFile(ctx) ?: return false
        return root.findFile(name) != null
    }
}
