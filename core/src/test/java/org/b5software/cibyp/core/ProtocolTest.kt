package org.b5software.cibyp.core
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONArray
class ProtocolTest {
    @Test fun onionAndPrivateOriginsOnly() {
        assertEquals("http://${"a".repeat(56)}.onion", normalizeAddress("http://${"a".repeat(56)}.onion/"))
        assertEquals("http://192.168.1.8:3456", normalizeAddress("http://192.168.1.8:3456"))
        for (address in listOf("http://example.com", "http://abc.onion", "http://10.evil.com", "http://192.168.evil.com", "http://10.evil.0.0.1", "https://user:pass@example.com", "https://example.com/path", "https://example.com?token=secret")) assertTrue(runCatching { normalizeAddress(address) }.isFailure)
    }
    @Test fun watchCannotReadOrModifyCredentials() {
        assertTrue(runCatching { WatchProtocol.validate("getSettings", JSONArray()) }.isFailure)
        assertTrue(runCatching { WatchProtocol.validate("ipc:invoke", JSONArray().put("llm:request")) }.isFailure)
        WatchProtocol.validate("ipc:invoke", JSONArray().put("updates:start"))
    }
    @Test fun sharedListDoesNotContainToken() { assertFalse(Device("one", "My desktop", "https://example.com", "secret").publicJson().toString().contains("secret")) }
}
