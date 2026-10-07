/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class BackendRequestsTest {
    @Test fun transportRetryKeepsTheLogicalRequestIdentity() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"result":{"ok":true,"accepted":true}}"""))
        server.start()
        val backend = Backend(Device("fixture", "Fixture", server.url("/").toString().trimEnd('/')))
        try {
            val result = backend.rpc("submitMessage", JSONArray().put("session").put("Hello")) as JSONObject
            assertTrue(result.getBoolean("accepted"))
            val first = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            val second = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(first.getString("id"), second.getString("id"))
            assertEquals(first.toString(), second.toString())
        } finally { backend.close(); server.shutdown() }
    }
    @Test fun cancellingTheScreenCancelsItsNetworkCall() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
        val backend = Backend(Device("fixture", "Fixture", server.url("/").toString().trimEnd('/')))
        try {
            val job = launch { backend.rpc("listSessions") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { backend.close(); server.shutdown() }
    }
    @Test fun expiredLoginIsNotRetriedOrParsedAsHtml() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(401).setBody("<html>Login required</html>")); server.start()
        val backend = Backend(Device("fixture", "Fixture", server.url("/").toString().trimEnd('/')))
        try {
            try { backend.rpc("snapshot"); fail("Expected authentication failure") }
            catch (failure: BackendFailure) { assertEquals(401, failure.code) }
            assertEquals(1, server.requestCount)
        } finally { backend.close(); server.shutdown() }
    }
}
