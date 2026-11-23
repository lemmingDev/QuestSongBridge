package com.questsongbridge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.questsongbridge.app.service.UploadService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var portText: TextView
    private lateinit var tokenText: TextView
    private lateinit var storageText: TextView
    private lateinit var logText: TextView
    private lateinit var qrImage: ImageView
    private val listener = { runOnUiThread { refresh() } }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            StorageManager.persistPermission(this, uri)
            ServerState.storageOk = StorageManager.isWritable(this)
            ServerState.storageName = StorageManager.displayName(this)
            ServerState.logMsg("Storage selected: ${ServerState.storageName}")
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        portText = findViewById(R.id.portText)
        tokenText = findViewById(R.id.tokenText)
        storageText = findViewById(R.id.storageText)
        logText = findViewById(R.id.logText)
        qrImage = findViewById(R.id.qrImage)

        findViewById<Button>(R.id.startBtn).setOnClickListener { startServer() }
        findViewById<Button>(R.id.stopBtn).setOnClickListener { stopServer() }
        findViewById<Button>(R.id.selectStorageBtn).setOnClickListener {
            val initial = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary:ModData/com.beatgames.beatsaber/Mods/SongCore/CustomLevels")
            picker.launch(initial)
        }
        findViewById<Button>(R.id.testWriteBtn).setOnClickListener {
            val ok = StorageManager.testWrite(this)
            ServerState.storageOk = ok
            ServerState.logMsg("Test write: ${if (ok) "OK" else "FAILED"}")
            refresh()
        }
        findViewById<Button>(R.id.regenTokenBtn).setOnClickListener {
            Settings.regenerateToken()
            ServerState.logMsg("Token regenerated")
            refresh()
        }
        findViewById<Button>(R.id.copyTokenBtn).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("QuestSongBridge token", Settings.getToken()))
            ServerState.logMsg("Token copied to clipboard")
        }
        findViewById<Button>(R.id.saveSettingsBtn).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.clearLogBtn).setOnClickListener {
            synchronized(ServerState.log) { ServerState.log.clear() }
            ServerState.notifyChanged()
        }

        // spinner
        val spinner = findViewById<Spinner>(R.id.dupSpinner)
        ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("skip", "replace", "cancel"))
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.adapter = it }
        spinner.setSelection(listOf("skip", "replace", "cancel").indexOf(Settings.getDuplicatePolicy()).coerceAtLeast(0))

        findViewById<EditText>(R.id.headsetNameEdit).setText(Settings.getHeadsetName())
        findViewById<EditText>(R.id.portEdit).setText(Settings.getPort().toString())
        findViewById<EditText>(R.id.maxUploadEdit).setText((Settings.getMaxUploadBytes() / 1024 / 1024).toString())
        findViewById<CheckBox>(R.id.retainZipCheck).isChecked = Settings.getRetainZip()

        ServerState.addListener(listener)
        refresh()
    }

    override fun onDestroy() {
        ServerState.removeListener(listener)
        super.onDestroy()
    }

    private fun refresh() {
        statusText.text = "Name: ${Settings.getHeadsetName()} | Status: ${ServerState.status}"
        portText.text = "Port: ${ServerState.port}"
        tokenText.text = "Token: ${Settings.getToken()}"
        storageText.text = "Storage: ${ServerState.storageName ?: "not configured"} (${if (ServerState.storageOk) "writable" else "not writable"})"
        // url
        val addr = ServerState.addresses.firstOrNull()
        val url = if (addr != null && ServerState.status == ServerStatus.RUNNING) {
            "http://$addr:${ServerState.port}/?t=${Settings.getToken()}"
        } else null
        if (url != null) {
            urlText.text = "URL: ${url.substringBefore("?")}"
            qrImage.setImageBitmap(generateQr(url))
        } else {
            urlText.text = "URL: -"
            qrImage.setImageBitmap(null)
        }
        // log
        val sb = StringBuilder()
        synchronized(ServerState.log) {
            ServerState.log.toList().takeLast(40).forEach { e ->
                sb.append(android.text.format.DateFormat.format("HH:mm:ss", e.time)).append(" ").append(e.message).append("\n")
            }
        }
        logText.text = sb.toString()
    }

    private fun startServer() {
        if (!StorageManager.hasSelection()) {
            ServerState.logMsg("Configure storage before starting server")
            refresh(); return
        }
        val intent = Intent(this, UploadService::class.java).setAction(UploadService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopServer() {
        val intent = Intent(this, UploadService::class.java).setAction(UploadService.ACTION_STOP)
        startService(intent)
    }

    private fun generateQr(content: String): android.graphics.Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val matrix = writer.encode(content, BarcodeFormat.QR_CODE, 400, 400)
            val w = matrix.width
            val h = matrix.height
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun saveSettings() {
        Settings.setHeadsetName(findViewById<EditText>(R.id.headsetNameEdit).text.toString())
        val port = findViewById<EditText>(R.id.portEdit).text.toString().toIntOrNull() ?: 8080
        val maxMb = findViewById<EditText>(R.id.maxUploadEdit).text.toString().toLongOrNull() ?: 200L
        Settings.setPort(port)
        Settings.setMaxUploadMb(maxMb)
        Settings.setDuplicatePolicy(findViewById<Spinner>(R.id.dupSpinner).selectedItem.toString())
        Settings.setRetainZip(findViewById<CheckBox>(R.id.retainZipCheck).isChecked)
        ServerState.port = port
        ServerState.logMsg("Settings saved")
        refresh()
    }
}
