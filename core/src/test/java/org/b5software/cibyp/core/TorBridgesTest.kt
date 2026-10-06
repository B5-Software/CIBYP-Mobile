/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import org.junit.Assert.*
import org.junit.Test

class TorBridgesTest {
    @Test fun meekAliasPreservesAllParameters() {
        val bridge = TorBridges.parse("Bridge " + TorBridges.DEFAULT_MEEK.replace("meek_lite", "meek")).single()
        assertEquals("meek_lite", bridge.transport)
        assertEquals(TorBridges.DEFAULT_MEEK, bridge.line)
    }
    @Test fun officialFormatAllowsOptionalFingerprintAndMultipleTargets() {
        val value = "meek_lite [2001:db8::1]:443 targets=https://bridge.example.org|front.example.org+front2.example.org,https://backup.example.org|backup-front.example.org utls=HelloRandomizedALPN"
        assertEquals(value, TorBridges.parse(value).single().line)
        assertEquals("snowflake", TorBridges.parse("snowflake 192.0.2.3:80").single().transport)
        assertTrue(TorBridges.parse("").isEmpty())
    }
    @Test fun invalidParametersAndTorrcInjectionAreRejected() {
        listOf("meek 192.0.2.20:80", "meek 192.0.2.20:80 url=file:///tmp/meek", "meek 192.0.2.20:80 targets=https://example.org", "meek 256.1.1.1:80 url=https://example.org", "meek 192.0.2.20:70000 url=https://example.org", TorBridges.DEFAULT_MEEK + "\u0000", TorBridges.DEFAULT_MEEK + "\nControlPort 1234", List(33) { TorBridges.DEFAULT_MEEK }.joinToString("\n")).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { TorBridges.parse(value) }
        }
    }
}
