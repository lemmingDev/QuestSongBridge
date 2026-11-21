package com.questsongbridge.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

data class SongInstallResult(
    val songName: String,
    val success: Boolean,
    val message: String
)

data class UploadResult(
    val success: Boolean,
    val message: String,
    val songs: List<SongInstallResult>
)

object ZipProcessor {
    private const val MAX_FILES = 2000
    private const val MAX_DEPTH = 12
    private const val MAX_UNCOMPRESSED = 1024L * 1024L * 1024L // 1GB

    fun process(ctx: Context, zipFile: File, originalName: String, duplicatePolicy: String = Settings.getDuplicatePolicy()): UploadResult {
        val maxBytes = Settings.getMaxUploadBytes()
        if (zipFile.length() > maxBytes) {
            return UploadResult(false, "Upload exceeds configured size limit", emptyList())
        }
        val staging = File(ctx.cacheDir, "staging/${UUID.randomUUID()}")
        staging.mkdirs()
        try {
            // Extract safely
            val extractResult = extract(zipFile, staging)
            if (extractResult != null) {
                return UploadResult(false, extractResult, emptyList())
            }
            // Detect songs
            val songs = detectSongs(staging, originalName)
            if (songs.isEmpty()) {
                return UploadResult(false, "No recognisable song metadata (info.dat/info.json) found", emptyList())
            }
            songs.forEach { normalizeSongFolder(it.dir) }
            val root = StorageManager.getDocumentFile(ctx)
                ?: return UploadResult(false, "Destination directory not configured", emptyList())
            if (!root.canWrite()) {
                return UploadResult(false, "Destination directory is not writable", emptyList())
            }
            val results = mutableListOf<SongInstallResult>()
            val policy = duplicatePolicy
            for (song in songs) {
                val targetName = song.name
                val exists = root.findFile(targetName) != null
                if (exists) {
                    when (policy) {
                        "skip" -> {
                            results.add(SongInstallResult(targetName, false, "Skipped: song already exists. Change Duplicate policy to 'replace' in app Settings to overwrite."))
                            continue
                        }
                        "replace" -> {
                            val existing = root.findFile(targetName)
                            existing?.delete()
                        }
                        else -> {
                            results.add(SongInstallResult(targetName, false, "Cancelled: duplicate song. Change Duplicate policy in app Settings to 'skip' or 'replace'."))
                            continue
                        }
                    }
                }
                val ok = installDir(ctx, song.dir, root, targetName)
                if (ok) {
                    results.add(SongInstallResult(targetName, true, "Installed"))
                } else {
                    results.add(SongInstallResult(targetName, false, "Installation failed"))
                }
            }
            val anySuccess = results.any { it.success }
            val msg = if (anySuccess) "Completed" else "No songs installed"
            // retain zip
            if (Settings.getRetainZip()) {
                val uploadsDir = File(ctx.cacheDir, "uploads")
                uploadsDir.mkdirs()
                zipFile.copyTo(File(uploadsDir, sanitizeFileName(originalName)), overwrite = true)
            }
            return UploadResult(anySuccess, msg, results)
        } finally {
            deleteRecursive(staging)
            try { zipFile.delete() } catch (e: Exception) { }
        }
    }

    private fun extract(zipFile: File, dest: File): String? {
        var fileCount = 0
        var totalSize = 0L
        try {
            ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (name.isBlank()) { entry = zis.nextEntry; continue }
                    // validate
                    if (name.startsWith("/") || name.startsWith("\\")) {
                        return "ZIP contains absolute path: $name"
                    }
                    val parts = name.replace("\\", "/").split("/")
                    if (parts.any { it == ".." || it == "." && false }) {
                        // reject traversal
                        if (parts.any { it == ".." }) return "ZIP path traversal detected: $name"
                    }
                    // depth
                    if (parts.size > MAX_DEPTH) return "Excessive directory nesting"
                    if (entry.isDirectory) {
                        // create dir
                        val dir = File(dest, name)
                        if (!dir.canonicalPath.startsWith(dest.canonicalPath)) return "ZIP path traversal detected"
                        dir.mkdirs()
                    } else {
                        val outFile = File(dest, name)
                        if (!outFile.canonicalPath.startsWith(dest.canonicalPath)) return "ZIP path traversal detected"
                        outFile.parentFile?.mkdirs()
                        // size check
                        var written = 0L
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(8192)
                            var r: Int
                            while (true) {
                                r = zis.read(buf)
                                if (r < 0) break
                                fos.write(buf, 0, r)
                                written += r
                                totalSize += r
                                if (totalSize > MAX_UNCOMPRESSED) return "Uncompressed size exceeds limit"
                            }
                        }
                        fileCount++
                        if (fileCount > MAX_FILES) return "Too many files in archive"
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return "Invalid ZIP archive: ${e.message}"
        }
        if (fileCount == 0) return "ZIP archive contains no files"
        return null
    }

    private data class Song(val name: String, val dir: File)

    private fun detectSongs(staging: File, originalName: String): List<Song> {
        val infoFiles = mutableListOf<File>()
        staging.walkTopDown().forEach { f ->
            val n = f.name.lowercase()
            if (f.isFile && (n == "info.dat" || n == "info.json" || n == "info.dat.json" || n.startsWith("info.dat") || n.startsWith("info.json"))) infoFiles.add(f)
        }
        if (infoFiles.isEmpty()) return emptyList()
        val roots = linkedSetOf<File>()
        for (info in infoFiles) {
            val parent = info.parentFile ?: staging
            roots.add(parent)
        }
        // If a root is ancestor of another, keep the deeper? Usually song folder is parent of info.dat.
        // Dedup: if one root is descendant of another, keep descendant.
        val filtered = roots.toList()
        val result = mutableListOf<Song>()
        for (root in filtered) {
            val targetName = if (root == staging) {
                sanitizeFileName(originalName.removeSuffix(".zip").removeSuffix(".ZIP"))
                    .ifBlank { "ImportedSong" }
            } else {
                sanitizeFileName(root.name).ifBlank { "ImportedSong" }
            }
            result.add(Song(targetName, root))
        }
        // dedup target names
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<Song>()
        for (s in result) {
            var name = s.name
            var i = 2
            while (seen.contains(name)) { name = "${s.name}_$i"; i++ }
            seen.add(name)
            unique.add(s.copy(name = name))
        }
        return unique
    }

    private fun normalizeSongFolder(dir: File) {
        val infoCandidate = dir.listFiles()?.firstOrNull { f ->
            val n = f.name.lowercase()
            n == "info.dat" || n == "info.json" || n == "info.dat.json" || n.startsWith("info.dat") || n.startsWith("info.json")
        } ?: return
        // Rename info.dat.json -> info.dat
        val infoFile = if (infoCandidate.name.lowercase() == "info.dat.json") {
            val t = File(dir, infoCandidate.name.removeSuffix(".json"))
            infoCandidate.renameTo(t)
            t
        } else {
            infoCandidate
        }
        if (!infoFile.exists()) return
        // If info.json (old format), leave as-is.
        if (infoFile.name.lowercase() == "info.json") return
        // Parse info.dat and normalize referenced beatmap/asset filenames.
        try {
            val json = JSONObject(infoFile.readText())
            val refs = linkedSetOf<String>()
            json.optString("_songFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
            json.optString("_coverImageFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
            // V4 format references
            json.optString("songPreviewFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
            json.optString("coverImageFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
            val audio = json.optJSONObject("audio")
            audio?.optString("songFilename")?.takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
            audio?.optString("audioDataFilename")?.takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
            val sets = json.optJSONArray("_difficultyBeatmapSets")
            if (sets != null) {
                for (i in 0 until sets.length()) {
                    val set = sets.optJSONObject(i) ?: continue
                    val beats = set.optJSONArray("_difficultyBeatmaps") ?: continue
                    for (j in 0 until beats.length()) {
                        val b = beats.optJSONObject(j) ?: continue
                        b.optString("_beatmapFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
                        b.optString("_lightshowFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
                    }
                }
            }
            val v4 = json.optJSONArray("difficultyBeatmaps")
            if (v4 != null) {
                for (i in 0 until v4.length()) {
                    val b = v4.optJSONObject(i) ?: continue
                    b.optString("beatmapDataFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
                    b.optString("lightshowDataFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
                }
            }
            json.optString("songFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }
            json.optString("coverImageFilename").takeIf { !it.isNullOrBlank() }?.let { refs.add(it) }

            for (ref in refs) {
                val target = File(dir, ref)
                if (!target.exists()) {
                    val withJson = File(dir, "$ref.json")
                    if (withJson.exists()) {
                        withJson.renameTo(target)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore normalization errors; keep original files.
        }
    }

    private fun installDir(ctx: Context, src: File, destParent: DocumentFile, destName: String): Boolean {
        val destDir = destParent.createDirectory(destName) ?: return false
        return copyRecursive(ctx, src, destDir)
    }

    private fun copyRecursive(ctx: Context, src: File, dest: DocumentFile): Boolean {
        if (src.isDirectory) {
            for (child in src.listFiles() ?: emptyArray()) {
                val name = child.name
                if (child.isDirectory) {
                    val sub = dest.findFile(name) ?: dest.createDirectory(name) ?: return false
                    if (!copyRecursive(ctx, child, sub)) return false
                } else {
                    val mime = "application/octet-stream"
                    val existing = dest.findFile(name)
                    val doc = existing ?: dest.createFile(mime, name) ?: return false
                    try {
                        ctx.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                            FileInputStream(child).use { inp -> inp.copyTo(out) }
                        } ?: return false
                    } catch (e: Exception) {
                        return false
                    }
                }
            }
            return true
        } else {
            return true
        }
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".json") || lower.endsWith(".dat") -> "application/json"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)
    }

    private fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }
}
