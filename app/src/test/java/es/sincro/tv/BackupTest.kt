package es.sincro.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El fichero de copia: lo que lleva con y sin claves. (La carga se prueba en el dispositivo: usa los ajustes de Android.) */
class BackupTest {
    @Test
    fun formatoDelFichero() {
        // Imita lo que exporta la app y comprueba que se puede volver a leer
        val o = JSONObject().put("app", "sincro-tv").put("formato", 1).put("conSecretos", false)
            .put("ajustes", JSONObject().put("radio", COPE_URL).put("delay", 31.5).put("team", Follow.DEFAULT_TEAMS))
            .put("asignaciones", JSONObject().put("#2510", org.json.JSONArray().put(JSONObject().put("name", "ES| DAZN 1 FHD").put("url", "u"))))
        val back = JSONObject(o.toString(2))
        assertEquals("sincro-tv", back.getString("app"))
        assertFalse(back.getJSONObject("ajustes").has("m3u"))
        assertFalse(back.getJSONObject("ajustes").has("wg"))
        assertEquals(31.5, back.getJSONObject("ajustes").getDouble("delay"), 0.001)
        assertTrue(back.getJSONObject("asignaciones").has("#2510"))
    }
}
