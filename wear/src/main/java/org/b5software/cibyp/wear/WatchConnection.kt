/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.wear

import kotlinx.coroutines.flow.StateFlow
import org.b5software.cibyp.core.Device
import org.json.JSONArray
import org.json.JSONObject

internal interface WatchConnection {
    val devices: StateFlow<List<Device>>
    val appearance: StateFlow<JSONObject>
    suspend fun start()
    fun selectDevice(device: String)
    suspend fun rpc(device: String, method: String, args: JSONArray = JSONArray()): Any?
    fun close()
}
