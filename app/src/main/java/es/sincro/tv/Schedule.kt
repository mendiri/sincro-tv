package es.sincro.tv

import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class Match(
    val date: String,          // dd/MM/yyyy
    val time: String,          // HH:mm (hora de España)
    val competition: String,
    val home: String,
    val away: String,
    val channels: List<String>,
    val sport: String = "Fútbol",
    /** Código de Live Guide de cada canal (mismo orden que [channels]); vacío si no se sabe. */
    val channelIds: List<String> = emptyList(),
) {
    /** Claves de los canales ("#código" o nombre), en el orden de [channels]. */
    val refs get() = channels.mapIndexed { i, n -> refOf(n, channelIds.getOrNull(i)) }
    val title get() = if (away.isBlank()) home else "$home – $away"
    /** ¿Juega alguno de tus equipos? (lista separada por comas) */
    fun involvesAny(teams: String) = teams.split(',', ';', '\n').any { it.isNotBlank() && involves(it.trim()) }

    val isFootball get() = sport.isBlank() || sport.equals("Fútbol", true) || sport.equals("Football", true)

    fun involves(team: String): Boolean {
        val t = Names.normalize(team)
        if (t.isBlank()) return false
        return Names.normalize(home) == t || Names.normalize(away) == t ||
            Names.normalize(home).contains(t) || Names.normalize(away).contains(t)
    }
}

/** Partidos y canales sacados de futbolenlatv.es. */
object Schedule {
    private val MADRID = TimeZone.getTimeZone("Europe/Madrid")

    fun fetch(): List<Match> {
        val req = Request.Builder().url("https://www.futbolenlatv.es/")
            .header("User-Agent", Http.WEB_UA)
            .header("Accept-Language", "es-ES,es;q=0.9")
            .build()
        val html = Http.client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IOException("La guía de partidos respondió con error ${it.code}")
            it.body!!.string()
        }
        val all = parse(html)
        if (all.isEmpty()) throw IOException("No he podido leer la guía de partidos (puede que la web haya cambiado)")
        return upcoming(all, Clock.madrid())
    }

    fun parse(html: String): List<Match> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<Match>()
        for (table in doc.select("table.tablaPrincipal")) {
            val head = table.selectFirst("tr.cabeceraTabla")?.text() ?: continue
            val date = Regex("\\d{2}/\\d{2}/\\d{4}").find(head)?.value ?: continue
            var comp = ""
            for (tr in table.select("tr")) {
                if (tr.hasClass("cabeceraCompericion")) { comp = tr.text().trim(); continue }
                val time = tr.selectFirst("td.hora")?.text()?.trim() ?: continue
                var home = tr.selectFirst("td.local")?.text()?.trim().orEmpty()
                val away = tr.selectFirst("td.visitante")?.text()?.trim().orEmpty()
                if (home.isBlank() && away.isBlank()) {
                    home = tr.selectFirst("td.detalles")?.text()?.trim().orEmpty()
                    if (home.isBlank()) continue
                }
                val channels = tr.select("ul.listaCanales li")
                    .map { cleanChannel(it.attr("title").ifBlank { it.text() }) }
                    .filter { it.isNotBlank() && !it.contains("ver en directo", true) }
                    .distinct()
                val rowComp = tr.selectFirst("td.detalles span.ajusteDoslineas")?.text()?.trim().orEmpty()
                out += Match(date, time, rowComp.ifBlank { comp }, home, away, channels)
            }
        }
        return out
    }

    /** Quita coletillas de la web: ": VER PARTIDO", "(Ver gratis)". */
    fun cleanChannel(s: String): String = s
        .replace(Regex("(?i)\\s*:\\s*ver partido.*$"), "")
        .replace(Regex("(?i)\\s*\\(ver gratis\\)"), "")
        .trim()

    /** Hoy (sin los que ya terminaron) y mañana, ordenados por día y hora. */
    fun upcoming(all: List<Match>, now: Calendar): List<Match> {
        val f = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = now.timeZone }
        val today = f.format(now.time)
        val tomorrow = f.format((now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
        val minuteNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return all.filter { m ->
            when (m.date) {
                today -> minutesOf(m.time)?.let { it + 125 >= minuteNow } ?: true
                tomorrow -> true
                else -> false
            }
        }.sortedWith(compareBy({ if (it.date == today) 0 else 1 }, { minutesOf(it.time) ?: 0 }))
    }

    fun minutesOf(t: String): Int? {
        val p = Regex("(\\d{1,2}):(\\d{2})").find(t) ?: return null
        return p.groupValues[1].toInt() * 60 + p.groupValues[2].toInt()
    }
}
