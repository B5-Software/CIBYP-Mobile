package org.b5software.cibyp.core
import org.junit.Test
import org.junit.Assert.*
import org.json.*

class WatchPayloadTest {
    @Test fun largeTranscriptsHaveABoundedWatchViewport() {
        val full = JSONObject().put("messages", JSONArray((0..100).map { JSONObject().put("id", "$it").put("role", "assistant").put("content", "🎉中".repeat(5000)).put("reasoning", "large private reasoning").put("attachments", JSONArray().put(JSONObject().put("name", "notes.md").put("path", "private/path"))) }))
        val watch = WatchPayload.details(full)
        assertEquals(WatchPayload.MAX_MESSAGES, watch.array("messages").length())
        assertTrue(watch.toString().toByteArray().size < 48000)
        assertFalse(watch.toString().contains("private"))
        assertTrue(watch.array("messages").getJSONObject(0).optBoolean("truncated"))
        assertEquals(101, full.array("messages").length())
    }
    @Test fun truncationDoesNotSplitEmoji() { assertEquals("🎉…", WatchPayload.text("🎉🎉", 1)) }
    @Test fun hardwareIsBoundedAndApprovalsKeepTheirIdentity() {
        val nodes = JSONArray((0..100).map { JSONObject().put("id", "$it").put("label", "x".repeat(1000)).put("online", true).put("deviceToken", "PRIVATE") })
        val payload = WatchPayload.hardware(nodes)
        assertEquals(16, payload.length()); assertFalse(payload.toString().contains("PRIVATE"))
        val interaction = WatchPayload.details(JSONObject().put("pendingInteraction", JSONObject().put("id", "request-1").put("revision", 7).put("kind", "approval"))).getJSONObject("pendingInteraction")
        assertEquals("request-1", interaction.getString("id")); assertEquals(7L, interaction.getLong("revision"))
    }
    @Test fun maximalFourByteContentAndSummariesStillFitTheDataLayer() {
        val large = "🎉".repeat(5000)
        val full = JSONObject().put("messages", JSONArray((0..20).map { JSONObject().put("id", "$it").put("role", "assistant").put("content", large).put("reasoningKind", "summary").put("reasoning", large).put("providerReasoning", JSONObject().put("encrypted_content", "OPAQUE_SECRET")).put("attachments", JSONArray((0..20).map { JSONObject().put("name", large).put("type", large) })) })).put("pendingInteraction", JSONObject().put("payload", JSONObject().put("description", large).put("command", large).put("message", large)))
        val watch = WatchPayload.details(full)
        assertTrue(watch.toString().toByteArray().size < 47000)
        assertFalse(watch.toString().contains("OPAQUE_SECRET"))
        assertTrue(watch.array("messages").getJSONObject(0).optString("reasoningSummary").isNotEmpty())
    }
}
