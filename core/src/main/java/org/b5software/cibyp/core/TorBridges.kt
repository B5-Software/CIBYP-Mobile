/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import java.net.URI

data class TorBridge(val transport: String, val line: String)

object TorBridges {
    // Tor Expert Bundle 15.0.24: tor/pluggable_transports/pt_config.json.
    const val DEFAULT_MEEK = "meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN"
    val transports = setOf("obfs4", "snowflake", "webtunnel", "meek_lite")
    fun parse(text: String): List<TorBridge> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size <= 32) { "Enter at most 32 bridge lines" }
        return lines.map { raw ->
            require(raw.length <= 4096 && raw.none { it.code < 32 || it.code == 127 }) { "Invalid Tor bridge line" }
            val parts = raw.replaceFirst(Regex("^Bridge\\s+", RegexOption.IGNORE_CASE), "").split(Regex(" +")).toMutableList()
            val transport = if (parts.first().matches(Regex("^[a-z][a-z\\d_]*$", RegexOption.IGNORE_CASE))) {
                parts.removeAt(0).lowercase().let { if (it == "meek") "meek_lite" else it }.also {
                    require(it in transports) { "Use obfs4, snowflake, webtunnel or meek bridges" }
                }
            } else ""
            val address = parts.removeFirstOrNull().orEmpty()
            val match = Regex("^(?:\\[([a-f\\d:]+)\\]|([\\d.]+)):(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(address)
            require(match != null && match.groupValues[3].toIntOrNull() in 1..65535) { "Invalid Tor bridge address" }
            val ipv4 = match.groupValues[2]
            require(ipv4.isEmpty() || (ipv4.split('.').size == 4 && ipv4.split('.').all { it.toIntOrNull() in 0..255 })) { "Invalid Tor bridge address" }
            val fingerprint = if (parts.firstOrNull()?.matches(Regex("^[a-f\\d]{40}$", RegexOption.IGNORE_CASE)) == true) parts.removeAt(0) else ""
            require(transport.isNotEmpty() || fingerprint.isNotEmpty()) { "A plain Tor bridge requires a fingerprint" }
            require(parts.all { it.matches(Regex("^[\\w-]+=\\S+$")) }) { "Invalid Tor bridge parameters" }
            val args = parts.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (transport == "meek_lite") {
                val urls = args["targets"]?.split(',')?.map {
                    require(it.indexOf('|') > 0) { "Invalid meek targets; use URL|front" }
                    it.substringBefore('|')
                } ?: listOf(args["url"].orEmpty())
                require(urls.first().isNotEmpty()) { "A meek bridge requires url= or targets=" }
                urls.forEach { value ->
                    val uri = runCatching { URI(value) }.getOrNull()
                    require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null) { "Invalid meek URL" }
                }
            }
            TorBridge(transport, (listOf(transport, address, fingerprint) + parts).filter(String::isNotEmpty).joinToString(" "))
        }
    }
}
