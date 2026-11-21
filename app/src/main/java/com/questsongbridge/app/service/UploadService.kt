package com.questsongbridge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.questsongbridge.app.MainActivity
import com.questsongbridge.app.R
import com.questsongbridge.app.ServerState
import com.questsongbridge.app.ServerStatus
import com.questsongbridge.app.Settings
import com.questsongbridge.app.http.HttpServer
import java.net.Inet4Address
import java.net.NetworkInterface

class UploadService : Service() {
    private var server: HttpServer? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        ServerState.status = ServerStatus.STARTING
        ServerState.notifyChanged()
        val port = Settings.getPort()
        ServerState.port = port
        // notification
        startForeground(NOTIF_ID, buildNotification("Starting..."))
        try {
            // acquire wifi lock to keep network
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "QuestSongBridge")
            lock.acquire()
            wifiLock = lock

            val mc = wm.createMulticastLock("QuestSongBridge")
            mc.setReferenceCounted(true)
            mc.acquire()
            multicastLock = mc

            val s = HttpServer(applicationContext, port)
            s.start()
            server = s
            // addresses
            ServerState.addresses = getIpAddresses()
            ServerState.status = ServerStatus.RUNNING
            ServerState.lastError = null
            ServerState.storageOk = com.questsongbridge.app.StorageManager.isWritable(applicationContext)
            ServerState.storageName = com.questsongbridge.app.StorageManager.displayName(applicationContext)
            ServerState.logMsg("Server started on port $port")
            registerMdns(port)
            updateNotification("Running on port $port")
        } catch (e: Exception) {
            server = null
            ServerState.status = ServerStatus.ERROR
            ServerState.lastError = e.message ?: "Failed to start server"
            ServerState.logMsg("Server failed: ${e.message}")
            updateNotification("Error: ${e.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        ServerState.notifyChanged()
    }

    private var wifiLock: WifiManager.WifiLock? = null

    private fun stopServer() {
        try { server?.stop() } catch (e: Exception) { }
        server = null
        unregisterMdns()
        try { wifiLock?.release() } catch (e: Exception) { }
        wifiLock = null
        try { multicastLock?.release() } catch (e: Exception) { }
        multicastLock = null
        ServerState.status = ServerStatus.STOPPED
        ServerState.logMsg("Server stopped")
        ServerState.notifyChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { server?.stop() } catch (e: Exception) { }
        server = null
        unregisterMdns()
        try { wifiLock?.release() } catch (e: Exception) { }
        try { multicastLock?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, UploadService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuestSongBridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "QuestSongBridge Server", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Keeps the upload server running"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun getIpAddresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        list.add(a.hostAddress ?: "")
                    }
                }
            }
        } catch (e: Exception) { }
        return list
    }

    private fun registerMdns(port: Int) {
        try {
            val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = nsd
            val info = NsdServiceInfo().apply {
                serviceName = "QuestSongBridge-${Settings.getHeadsetName()}"
                serviceType = "_http._tcp."
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                    ServerState.logMsg("mDNS registered: ${nsdServiceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    ServerState.logMsg("mDNS registration failed: $errorCode")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            nsdListener = listener
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            ServerState.logMsg("mDNS error: ${e.message}")
        }
    }

    private fun unregisterMdns() {
        try {
            nsdListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) { }
        nsdListener = null
        nsdManager = null
    }

    companion object {
        const val ACTION_START = "com.questsongbridge.app.action.START"
        const val ACTION_STOP = "com.questsongbridge.app.action.STOP"
        const val CHANNEL_ID = "questsongbridge_server"
        const val NOTIF_ID = 1001
    }
}
