/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import IPtProxy.Controller
import IPtProxy.IPtProxy
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/** Exercise the shipped native library, without claiming censored-network reachability. */
class MeekTransportTest {
    @Test fun bundledMeekStartsARealSocksListener() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = File(context.cacheDir, "meek-instrumentation").apply { mkdirs() }
        val controller = Controller(state.absolutePath, false, false, "ERROR", null)
        try {
            assertEquals("lyrebird-0.8.1", IPtProxy.lyrebirdVersion())
            controller.start(IPtProxy.MeekLite, "")
            val port = controller.port(IPtProxy.MeekLite).toInt()
            assertTrue(port in 1..65535)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().apply { write(byteArrayOf(5, 1, 2)); flush() }
                assertEquals(5, socket.getInputStream().read())
                assertEquals(2, socket.getInputStream().read())
            }
        } finally { controller.stop(IPtProxy.MeekLite) }
    }
}
