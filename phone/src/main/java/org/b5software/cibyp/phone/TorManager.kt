/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.content.*
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.torproject.jni.TorService
import IPtProxy.Controller
import IPtProxy.OnTransportEvents

class TorManager(private val context: Context) {
    val progress = MutableStateFlow("")
    private var bound = false
    private var service: TorService? = null
    private var ready = CompletableDeferred<TorService>()
    private var port: Int? = null
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
            service = (binder as TorService.LocalBinder).service
            ready.complete(service!!)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; port = null }
    }
    suspend fun start(bridges: String): Int = withContext(Dispatchers.IO) {
        port?.let { return@withContext it }
        val lines = bridges.lineSequence().map { it.trim().removePrefix("Bridge ") }.filter { it.isNotEmpty() }.toList()
        require(lines.size <= 32 && lines.none { it.any { char -> char.code < 32 } }) { "Invalid bridge lines" }
        val config = mutableListOf("SocksPort auto", "ClientOnly 1", "SafeSocks 1", "AvoidDiskWrites 1", "Log notice stdout")
        if (lines.isNotEmpty()) {
            for (line in lines) {
                val type = line.substringBefore(' ')
                require(type in setOf("obfs4", "snowflake", "webtunnel")) { "Use obfs4, snowflake or webtunnel bridges" }
                if (transports.add(type)) controller.start(type, "")
            }
            config.add("UseBridges 1")
            transports.forEach { config.add("ClientTransportPlugin $it socks5 127.0.0.1:${controller.port(it)}") }
            lines.forEach { config.add("Bridge $it") }
        }
        if (!bound) {
            TorService.getTorrc(context).writeText(config.joinToString("\n") + "\n")
            withContext(Dispatchers.Main) {
                check(context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)) { "Unable to start bundled Tor" }
                bound = true
            }
        }
        progress.value = "Starting Tor…"
        withTimeout(180000) {
            val tor = ready.await()
            while (true) {
                check(bound && service === tor) { "Tor connection cancelled" }
                val status = runCatching { tor.getInfo("status/bootstrap-phase") }.getOrNull().orEmpty()
                val percent = Regex("PROGRESS=(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                progress.value = "Tor $percent%"
                if (percent == 100 && tor.socksPort > 0) { port = tor.socksPort; break }
                delay(500)
            }
        }
        port!!
    }
    fun stop() {
        if (bound) context.unbindService(connection)
        context.stopService(Intent(context, TorService::class.java))
        bound = false; service = null; port = null; ready.cancel(); ready = CompletableDeferred()
        transports.forEach { controller.stop(it) }; transports.clear()
    }
}
