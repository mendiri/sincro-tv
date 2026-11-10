package es.sincro.tv

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Programación de Live Guide (sport-tv-guide.live), con hora de Madrid y canales de España. */
object LiveGuide {
    private const val BASE = "https://sport-tv-guide.live"
    private const val SPAIN = "16"

    /** Países que ofrece la web (código, nombre). */
    val COUNTRIES = listOf(
        "16" to "España", "2" to "Reino Unido", "19" to "Portugal", "17" to "Italia", "4" to "Alemania", "18" to "Francia",
        "10" to "Países Bajos", "33" to "Bélgica", "6" to "Suiza", "5" to "Austria", "15" to "Irlanda", "76" to "Luxemburgo",
        "21" to "Suecia", "22" to "Noruega", "28" to "Dinamarca", "20" to "Finlandia", "72" to "Islandia",
        "30" to "Polonia", "31" to "Rep. Checa", "32" to "Eslovaquia", "34" to "Hungría", "42" to "Rumanía", "50" to "Bulgaria",
        "24" to "Croacia", "23" to "Eslovenia", "25" to "Serbia", "38" to "Bosnia", "36" to "Albania", "26" to "Grecia",
        "27" to "Chipre", "73" to "Malta", "35" to "Turquía", "37" to "Rusia", "41" to "Ucrania", "47" to "Bielorrusia",
        "46" to "Estonia", "40" to "Letonia", "45" to "Lituania", "74" to "Georgia", "59" to "Azerbaiyán", "71" to "Kazajistán",
        "1" to "EE. UU.", "7" to "Canadá", "44" to "México", "75" to "Rep. Dominicana", "67" to "Guatemala", "69" to "El Salvador",
        "68" to "Honduras", "81" to "Nicaragua", "79" to "Costa Rica", "83" to "Panamá", "62" to "Colombia", "65" to "Venezuela",
        "80" to "Ecuador", "63" to "Perú", "78" to "Bolivia", "64" to "Chile", "61" to "Argentina", "66" to "Paraguay",
        "82" to "Uruguay", "60" to "Brasil",
        "58" to "Marruecos", "85" to "Egipto", "43" to "Nigeria", "57" to "Kenia", "14" to "Sudáfrica",
        "48" to "Israel", "39" to "Catar", "12" to "Arabia Saudí", "70" to "Pakistán", "9" to "India",
        "84" to "China", "52" to "Hong Kong", "56" to "Macao", "54" to "Taiwán", "77" to "Japón", "53" to "Tailandia",
        "55" to "Vietnam", "13" to "Myanmar", "11" to "Malasia", "8" to "Singapur", "51" to "Indonesia", "49" to "Filipinas",
        "3" to "Australia", "29" to "Nueva Zelanda",
        "101" to "Streams Europa", "102" to "Streams mundo",
    )

    /** Todos los países (la web solo deja marcar 15 a mano, pero por cookie admite todos). */
    val ALL_COUNTRIES = COUNTRIES.joinToString(",") { it.first }
    private val MADRID = TimeZone.getTimeZone("Europe/Madrid")

    private class Jar : CookieJar {
        val cookies = LinkedHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { for (c in cookies) this.cookies[c.name] = c }
        override fun loadForRequest(url: HttpUrl) = cookies.values.toList()
        fun value(name: String) = cookies[name]?.value?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    fun fetch(): List<Match> {
        val p = App.instance.prefs
        val res = fetch(p.countries.split(',').map { it.trim() }.filter { it.isNotBlank() })
        p.knownSports = (p.knownSports.split(',') + res.map { it.sport })
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted().joinToString(",")
        return res
    }

    fun fetch(countries: List<String>): List<Match> {
        val want = countries.ifEmpty { listOf(SPAIN) }
        val now = Calendar.getInstance(MADRID)
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = MADRID }
        val es = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = MADRID }
        val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val pages = java.util.concurrent.Executors.newFixedThreadPool(8)
        val days = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            // Hoy y mañana a la vez, cada uno con su propia sesión
            val today = days.submit<List<Match>> { day(want, es.format(now.time), null, pages) }
            val next = days.submit<List<Match>> { day(want, es.format(tomorrow.time), iso.format(tomorrow.time), pages) }
            val out = today.get() + runCatching { next.get() }.getOrDefault(emptyList())
            if (out.isEmpty()) throw IOException("No he podido leer la programación de Live Guide (puede que la web haya cambiado)")
            return Schedule.upcoming(out, now)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } finally {
            pages.shutdownNow(); days.shutdownNow()
        }
    }

    /** Un día de la guía: la primera página y las siguientes (pedidas de 6 en 6 a la vez). */
    private fun day(countries: List<String>, date: String, isoDate: String?, pool: java.util.concurrent.ExecutorService): List<Match> {
        val jar = Jar()
        // Países, hora de Madrid y formato 24 h van en las cookies: no hace falta configurar nada en la web
        val host = "sport-tv-guide.live"
        fun ck(n: String, v: String) = Cookie.Builder().domain(host).path("/").name(n).value(v).build()
        jar.cookies["mycountries"] = ck("mycountries", java.net.URLEncoder.encode(countries.joinToString(","), "UTF-8"))
        jar.cookies["user_time_zone"] = ck("user_time_zone", "Europe%2FMadrid")
        jar.cookies["time_format_12"] = ck("time_format_12", "0")
        val client = Http.client.newBuilder().cookieJar(jar).build()
        fun get(path: String): String = client.newCall(
            Request.Builder().url(BASE + path).header("User-Agent", Http.WEB_UA).build()
        ).execute().use {
            if (!it.isSuccessful) throw IOException("Live Guide respondió con error ${it.code}")
            it.body!!.string()
        }
        fun post(path: String, vararg kv: Pair<String, String>): String {
            val body = FormBody.Builder().apply { kv.forEach { (k, v) -> add(k, v) } }.build()
            return client.newCall(
                Request.Builder().url(BASE + path).post(body).header("User-Agent", Http.WEB_UA).header("X-Requested-With", "XMLHttpRequest").build()
            ).execute().use { it.body?.string().orEmpty() }
        }

        var first = get("/es")
        if (isoDate != null) {
            if (post("/ajaxdata/selectdate", "d" to isoDate).trim() != "ok") return emptyList()
            first = get("/es")
        }
        val seen = HashSet<String>()
        val events = ArrayList<Match>()   // partidos con su ficha
        val programs = ArrayList<Match>() // entradas de la parrilla de TV
        fun add(html: String) {
            for ((k, m) in parseKeyed(html, date)) if (seen.add(k)) (if (k.startsWith("/")) events else programs) += m
        }
        add(first)
        var p = 1
        var done = false
        while (!done && p <= 30) {
            val batch = (p until p + 6).map { n ->
                pool.submit<org.json.JSONObject?> {
                    try { org.json.JSONObject(post("/ajaxdata/nextlistpage", "l" to "0", "p" to n.toString(), "c" to "-1", "t" to "99", "d" to "", "x" to "0")) } catch (e: Exception) { null }
                }
            }
            for (f in batch) {
                val j = f.get()
                if (j == null) { done = true; break }
                add(j.optString("data"))
                if (j.optBoolean("last", true)) { done = true; break }
            }
            p += 6
        }
        // La parrilla repite partidos que ya están: se quitan los que coinciden en hora y equipos
        val extra = programs.filter { pr ->
            val text = Names.normalize(pr.title + " " + pr.competition)
            events.none { e -> e.time == pr.time && Names.normalize(e.home).let { it.isNotBlank() && text.contains(it) } }
        }
        return dedupe(events + extra)
    }

    /** Quita repetidos: misma hora y mismos equipos (se queda el de nombre más corto, que suele ser el limpio). */
    fun dedupe(list: List<Match>): List<Match> {
        val kept = ArrayList<Match>()
        for (m in list.sortedBy { it.title.length + it.competition.length }) {
            val text = Names.normalize(m.title + " " + m.competition)
            val dup = kept.any { k ->
                k.date == m.date && k.time == m.time && k.home.isNotBlank() &&
                    text.contains(Names.normalize(k.home)) && (k.away.isBlank() || text.contains(Names.normalize(k.away)))
            }
            if (!dup) kept += m
        }
        return kept.sortedWith(compareBy({ it.date }, { it.time }))
    }

    /** Lee los eventos de una página de la guía. [date] en formato dd/MM/yyyy. */
    fun parse(html: String, date: String): List<Match> = parseKeyed(html, date).map { it.second }

    private fun parseKeyed(html: String, date: String): List<Pair<String, Match>> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<Pair<String, Match>>()
        for (a in doc.select(".article")) {
            val time = to24h(a.selectFirst("b")?.text().orEmpty()) ?: continue
            val sport = a.selectFirst("img[data-src~=/images/t\\d+\\.png], img[src~=/images/t\\d+\\.png]")?.attr("title").orEmpty()
            val lines = a.select("div.col-inline div.text-nowrap").map { it.text().trim() }
                .ifEmpty { a.select("div.text-nowrap").map { it.text().trim() } }
            val event = lines.getOrNull(0).orEmpty()
            val league = lines.getOrNull(1).orEmpty()
            if (event.isBlank()) continue
            // Cada canal con su código de Live Guide (el número del final del logo: "...-1686.png")
            val stations = a.select("img[src*=tvm-station], img[data-src*=tvm-station]").map { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                img.attr("title").replace("[]", "").trim() to (Regex("-(\\d+)\\.png").find(src)?.groupValues?.get(1) ?: "")
            }.filter { it.first.isNotBlank() }.distinctBy { refOf(it.first, it.second) }
            val channels = stations.map { it.first }
            val parts = event.split(" - ", limit = 2)
            val key = a.attr("href").ifBlank { a.attr("data-id") } + "|" + time + "|" + event
            out += key to Match(date, time, league, parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty(), channels,
                sport.ifBlank { "Fútbol" }.let { if (it.equals("Football", true)) "Fútbol" else it }, stations.map { it.second })
        }
        return out
    }

    /** "6:00PM" -> "18:00"; "18:00" -> "18:00". */
    fun to24h(s: String): String? {
        val m = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?").find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val ampm = m.groupValues[3].uppercase()
        if (ampm == "PM" && h < 12) h += 12
        if (ampm == "AM" && h == 12) h = 0
        return "%02d:%s".format(h, m.groupValues[2])
    }
}
