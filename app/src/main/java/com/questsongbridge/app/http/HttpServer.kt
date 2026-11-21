package com.questsongbridge.app.http

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import com.questsongbridge.app.ServerState
import com.questsongbridge.app.Settings
import com.questsongbridge.app.StorageManager
import com.questsongbridge.app.ZipProcessor
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

class HttpServer(
    private val ctx: Context,
    port: Int
) : NanoHTTPD(port) {

    private val sessions = ConcurrentHashMap<String, Long>()
    private val uploadLock = Any()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        try {
            // CORS / origin check basic
            if (method == Method.OPTIONS) {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply { addCors() }
            }
            // Static + API routing
            return when {
                uri == "/" || uri == "/index.html" -> serveAsset("index.html")
                uri.startsWith("/assets/") -> serveAsset(uri.removePrefix("/assets/"))
                uri == "/api/status" -> handleStatus()
                uri == "/api/recent" -> handleRecent(session)
                uri == "/api/upload" && method == Method.POST -> handleUpload(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            ServerState.logMsg("HTTP error: ${e.message}")
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error")
        }
    }

    private fun Response.addCors() {
        this.addHeader("Access-Control-Allow-Origin", "*")
        this.addHeader("Access-Control-Allow-Headers", "X-Auth-Token, Content-Type")
        this.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    }

    private fun serveAsset(name: String): Response {
        return try {
            val input = ctx.assets.open(name)
            val mime = guessMime(name)
            newChunkedResponse(Response.Status.OK, mime, input).apply { addCors() }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun handleStatus(): Response {
        val obj = JSONObject()
        obj.put("version", "1.0.0")
        obj.put("headsetName", Settings.getHeadsetName())
        obj.put("status", ServerState.status.name)
        obj.put("port", ServerState.port)
        obj.put("storageOk", StorageManager.isWritable(ctx))
        obj.put("storageName", StorageManager.displayName(ctx) ?: "")
        obj.put("addresses", JSONArray(ServerState.addresses))
        obj.put("uploadsInProgress", ServerState.uploadsInProgress)
        // Do not expose the token over the unauthenticated status endpoint.
        val r = newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
        r.addCors()
        return r
    }

    private fun handleRecent(session: IHTTPSession): Response {
        val token = session.headers["x-auth-token"] ?: session.headers["X-Auth-Token"]
        if (!authOk(token)) {
            val r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"unauthorized\"}")
            r.addCors(); return r
        }
        val arr = JSONArray()
        synchronized(ServerState.recentUploads) {
            for (s in ServerState.recentUploads) arr.put(s)
        }
        val r = newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
        r.addCors(); return r
    }

    private fun handleUpload(session: IHTTPSession): Response {
        // auth
        val token = session.headers["x-auth-token"] ?: session.headers["X-Auth-Token"]
        if (!authOk(token)) {
            val r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"invalid or missing token\"}")
            r.addCors()
            return r
        }
        // origin check (advisory)
        // parse multipart
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tmpPath = files["file"] ?: files.values.firstOrNull()
        if (tmpPath == null) {
            val r = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"no file uploaded\"}")
            r.addCors(); return r
        }
        val tmpFile = File(tmpPath)
        val originalName = session.parameters["file"]?.firstOrNull() ?: tmpFile.name
        // concurrency guard
        ServerState.uploadsInProgress++
        ServerState.notifyChanged()
        val replace = session.parameters["replace"]?.firstOrNull()?.equals("true", ignoreCase = true) == true ||
            session.parameters["replace"]?.firstOrNull() == "1"
        val policy = if (replace) "replace" else Settings.getDuplicatePolicy()
        val result = try {
            synchronized(uploadLock) {
                ZipProcessor.process(ctx, tmpFile, originalName, policy)
            }
        } finally {
            ServerState.uploadsInProgress--
            ServerState.notifyChanged()
        }
        val obj = JSONObject()
        obj.put("success", result.success)
        obj.put("message", result.message)
        val arr = JSONArray()
        for (s in result.songs) {
            val o = JSONObject()
            o.put("song", s.songName)
            o.put("success", s.success)
            o.put("message", s.message)
            arr.put(o)
        }
        obj.put("songs", arr)
        ServerState.addUploadResult("${originalName}: ${result.message}")
        val r = newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
        r.addCors()
        return r
    }

    @Volatile private var authFailures = 0
    @Volatile private var authBlockedUntil = 0L

    private fun authOk(token: String?): Boolean {
        val now = System.currentTimeMillis()
        if (now < authBlockedUntil) return false
        if (token.isNullOrBlank()) { recordAuthFailure(); return false }
        val ok = token == Settings.getToken()
        if (!ok) recordAuthFailure() else authFailures = 0
        return ok
    }

    private fun recordAuthFailure() {
        authFailures++
        if (authFailures >= 5) {
            authBlockedUntil = System.currentTimeMillis() + 30000
            authFailures = 0
        }
    }

    private fun getTokenFromHeader(session: IHTTPSession?): String? = null

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".html") -> "text/html"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }
}
