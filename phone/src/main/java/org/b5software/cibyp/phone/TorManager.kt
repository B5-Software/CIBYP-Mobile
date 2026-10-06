/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.content.*
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.torproject.jni.TorService
import IPtProxy.Controller
import IPtProxy.OnTransportEvents
import org.b5software.cibyp.core.TorBridges
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TorManager(private val context: Context) {
    val progress = MutableStateFlow("")
    @Volatile private var bound = false
    @Volatile private var service: TorService? = null
    private var ready = CompletableDeferred<TorService>()
    @Volatile private var port: Int? = null
    private val startLock = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val transportLock = Any()
    private val transports = mutableSetOf<String>()
    private val controller by lazy {
        Controller(java.io.File(context.noBackupFilesDir, "transports").apply { mkdirs() }.absolutePath, false, false, "WARN", object : OnTransportEvents {
            override fun connected(name: String?) {}
            override fun error(name: String?, error: Exception?) { progress.value = error?.message ?: "Bridge connection failed" }
            override fun stopped(name: String?, error: Exception?) {}
        })
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!bound) return
            service = (binder as TorService.LocalBinder).service
            ready.complete(service!!)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; port = null }
    }
    suspend fun start(bridges: String): Int = startLock.withLock { withContext(Dispatchers.IO) {
        port?.let { return@withContext it }
        val current = generation.get()
        val readyForStart = ready
        try {
        val lines = TorBridges.parse(bridges)
        val config = mutableListOf("SocksPort auto", "ClientOnly 1", "SafeSocks 1", "AvoidDiskWrites 1", "Log notice stdout")
        if (lines.isNotEmpty()) {
            synchronized(transportLock) {
                check(current == generation.get()) { "Tor connection cancelled" }
                for (type in lines.map { it.transport }.filter(String::isNotEmpty).distinct()) {
                    if (type !in transports) {
                        controller.start(type, "")
                        check(controller.port(type) > 0) { "Unable to start $type bridge transport" }
                        transports.add(type)
                    }
                }
                config.add("UseBridges 1")
                transports.forEach { config.add("ClientTransportPlugin $it socks5 127.0.0.1:${controller.port(it)}") }
            }
            lines.forEach { config.add("Bridge ${it.line}") }
        }
        if (!bound) {
            TorService.getTorrc(context).writeText(config.joinToString("\n") + "\n")
            withContext(Dispatchers.Main) {
                check(current == generation.get()) { "Tor connection cancelled" }
                check(context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)) { "Unable to start bundled Tor" }
                bound = true
            }
        }
        progress.value = "Starting Tor…"
        withTimeout(if (lines.any { it.transport == "meek_lite" }) 600000 else 180000) {
            val tor = readyForStart.await()
            var previousProgress = -1
            var progressDeadline = android.os.SystemClock.elapsedRealtime() + 180000
            while (true) {
                check(current == generation.get() && bound && service === tor) { "Tor connection cancelled" }
                val status = runCatching { tor.getInfo("status/bootstrap-phase") }.getOrNull().orEmpty()
                val percent = Regex("PROGRESS=(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (percent > previousProgress) { previousProgress = percent; progressDeadline = android.os.SystemClock.elapsedRealtime() + 180000 }
                check(android.os.SystemClock.elapsedRealtime() < progressDeadline) { "Tor bootstrap stalled; check the network or bridge" }
                progress.value = "Tor $percent%"
                if (percent == 100 && tor.socksPort > 0) { port = tor.socksPort; break }
                delay(500)
            }
        }
        port!!
        } catch (failure: Exception) {
            if (current == generation.get()) {
                stop()
                progress.value = if (failure is TimeoutCancellationException) "Tor bootstrap timed out; check the network or bridge" else failure.message.orEmpty()
            }
            throw failure
        }
    } }
    fun stop() {
        generation.incrementAndGet()
        val wasBound = bound; bound = false
        if (wasBound) context.unbindService(connection)
        context.stopService(Intent(context, TorService::class.java))
        bound = false; service = null; port = null; ready.cancel(); ready = CompletableDeferred()
        synchronized(transportLock) { transports.forEach { controller.stop(it) }; transports.clear() }
    }
}
