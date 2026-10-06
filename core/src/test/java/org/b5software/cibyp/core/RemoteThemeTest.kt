package org.b5software.cibyp.core
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class RemoteThemeTest {
    @Test fun targetSystemModeOverridesPhoneSystemMode() {
        val state = JSONObject("""{"shouldUseDarkColors":false,"theme":{"mode":"system","accentColor":"#000000","backgroundColor":"#ffffff"},"apiKey":"private"}""")
        val public = publicRemoteTheme(state)
        val palette = RemotePalette.from(public, true)
        assertFalse(palette.dark)
        assertEquals(0xff000000L, palette.text)
        assertEquals(0xffffffffL, palette.onAccent)
        assertFalse(public.toString().contains("private"))
    }
    @Test fun extremeBackgroundsRemainReadable() {
        for (dark in listOf(false, true)) {
            val palette = RemotePalette.from(JSONObject().put("mode", if (dark) "dark" else "light").put("backgroundColor", if (dark) "#050505" else "#ffffff"), !dark)
            assertEquals(dark, palette.dark)
            assertEquals(if (dark) 0xffffffffL else 0xff000000L, palette.text)
        }
        assertNull(parseThemeColor("red; url(private)"))
    }
}
