package com.questsongbridge.app

enum class ServerStatus { STOPPED, STARTING, RUNNING, ERROR }

data class LogEntry(val time: Long, val message: String)

object ServerState {
    @Volatile var status: ServerStatus = ServerStatus.STOPPED
    @Volatile var port: Int = 8080
    @Volatile var addresses: List<String> = emptyList()
    @Volatile var lastError: String? = null
    @Volatile var storageOk: Boolean = false
    @Volatile var storageName: String? = null
    @Volatile var uploadsInProgress: Int = 0
    val recentUploads = mutableListOf<String>()
    val log = ArrayDeque<LogEntry>(200)

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    fun notifyChanged() { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    fun logMsg(msg: String) {
        synchronized(log) {
            log.addLast(LogEntry(System.currentTimeMillis(), msg))
            while (log.size > 200) log.removeFirst()
        }
        notifyChanged()
    }
    fun addUploadResult(msg: String) {
        synchronized(recentUploads) {
            recentUploads.add(0, msg)
            while (recentUploads.size > 50) recentUploads.removeLast()
        }
        notifyChanged()
    }
}
