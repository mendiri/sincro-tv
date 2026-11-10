package es.sincro.tv

import java.text.Normalizer

/**
 * Lo que sigues, como en Live Guide: ligas y equipos/jugadores, cada uno con su deporte.
 * Formato (una por línea): "Fútbol: Champions League", "Tenis: Carlos Alcaraz".
 */
object Follow {
    data class Entry(val sport: String?, val tokens: Set<String>, val label: String)

    const val DEFAULT_LEAGUES = """Fútbol: LaLiga EA Sports
Fútbol: LaLiga Hypermotion
Fútbol: Copa del Rey
Fútbol: Champions League
Fútbol: Europa League
Fútbol: Europa Conference League
Fútbol: UEFA Nations League
Fútbol: Juegos olímpicos
Fútbol: Amistoso internacional
Baloncesto: ACB
Baloncesto: Euroleague
Balonmano: Asobal
Balonmano: Champions League"""

    const val DEFAULT_TEAMS = """Fútbol: Real Madrid
Fútbol: Barcelona
Fútbol: Zaragoza
Fútbol: España
Fútbol: España (F)
Baloncesto: España
Baloncesto: España (F)
Baloncesto: España 3x3
Tenis: Carlos Alcaraz
Tenis: Paula Badosa
Tenis: Garbiñe Muguruza
Tenis: España
MMA: Ilia Topuria"""

    private val ALIAS = mapOf(
        "spain" to "espana", "espanola" to "espana",
        "euroliga" to "euroleague", "endesa" to "acb",
        "friendly" to "amistoso", "friendlies" to "amistoso", "amistosos" to "amistoso",
        "international" to "internacional", "internacionales" to "internacional",
        "olympic" to "olimpicos", "olympics" to "olimpicos", "olimpico" to "olimpicos", "olimpicas" to "olimpicos",
        "games" to "juegos", "women" to "fem", "femenino" to "fem", "femenina" to "fem", "w" to "fem",
    )
    private val STOP = setOf("de", "del", "la", "el", "the", "seleccion", "sel")
    /** Palabras que pueden sobrar sin que deje de ser lo mismo ("FC Barcelona", "UEFA Champions League", "Nations League A"). */
    private val EXTRA_OK = setOf("fc", "cf", "cd", "sd", "ud", "rc", "rcd", "sad", "club", "real", "uefa", "fifa", "fiba", "ehf", "liga")

    private val SPORT = mapOf(
        "football" to "futbol", "soccer" to "futbol", "basketball" to "baloncesto", "handball" to "balonmano",
        "tennis" to "tenis", "ufc" to "mma",
    )

    fun tokens(s: String): Set<String> {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        t = t.replace(Regex("\\(\\s*f\\s*\\)|\\(\\s*w\\s*\\)"), " fem ")
        t = t.replace(Regex("\\[.*?]|\\(.*?\\)"), " ")
        t = t.replace(Regex("\\bla\\s+liga\\b"), "laliga")
        return t.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.map { ALIAS[it] ?: it }.filter { it !in STOP }.toSet()
    }

    private fun sportKey(s: String): String {
        val n = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return SPORT[n] ?: n
    }

    fun parse(text: String): List<Entry> = text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { line ->
        val i = line.indexOf(':')
        val (sport, name) = if (i in 1..20 && line.substring(0, i).none { it.isDigit() }) line.substring(0, i).trim() to line.substring(i + 1).trim() else null to line
        val ts = tokens(name)
        if (ts.isEmpty()) null else Entry(sport?.let { sportKey(it) }, ts, line)
    }

    private fun sportOk(e: Entry, m: Match) = e.sport == null || e.sport == sportKey(m.sport)

    /** ¿Es la misma cosa? Una contiene a la otra y lo que sobra son palabras sin importancia. */
    private fun same(followed: Set<String>, event: Set<String>): Boolean {
        if (event.isEmpty()) return false
        if (followed.containsAll(event) && event.any { it.length >= 3 }) return true
        if (event.containsAll(followed)) {
            val extra = event - followed
            return extra.all { it in EXTRA_OK || it.length == 1 }
        }
        return false
    }

    fun followsLeague(leagues: List<Entry>, m: Match): Boolean {
        val ev = tokens(m.competition)
        return leagues.any { sportOk(it, m) && same(it.tokens, ev) }
    }

    fun followsTeam(teams: List<Entry>, m: Match): Boolean {
        val sides = listOf(m.home, m.away) + m.competition.split(" - ", " – ", " vs ", " vs. ")
        return teams.any { e -> sportOk(e, m) && sides.any { same(e.tokens, tokens(it)) } }
    }
}
