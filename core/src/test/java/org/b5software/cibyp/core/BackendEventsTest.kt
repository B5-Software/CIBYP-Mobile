/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackendEventsTest {
    @Test fun resetAcceptsNewSequenceAndIntentionalCloseDoesNotReconnect() {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                socket.send("""{"sequence":5,"channel":"first"}""")
                socket.send("""{"sequence":2,"channel":"duplicate"}""")
                socket.send("""{"type":"reset","sequence":1}""")
                socket.send("""{"sequence":2,"channel":"new-backend"}""")
            }
        }))
        server.start()
        val backend = Backend(Device("fixture", "Fixture", server.url("/").toString().trimEnd('/'), "fixture-token"))
        try {
            val received = Collections.synchronizedList(mutableListOf<String>())
            val ready = CountDownLatch(3)
            val closed = AtomicInteger()
            backend.subscribe({ event -> received.add(event.optString("type", event.optString("channel"))); ready.countDown() }, { closed.incrementAndGet() })
            assertTrue("Expected replay reset and new backend event", ready.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("first", "reset", "new-backend"), received.toList())
            backend.close()
            Thread.sleep(100)
            assertEquals("Intentional close must not request reconnect", 0, closed.get())
        } finally { backend.close(); server.shutdown() }
    }
}
