package es.sincro.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowTest {
    private val leagues = Follow.parse(Follow.DEFAULT_LEAGUES)
    private val teams = Follow.parse(Follow.DEFAULT_TEAMS)
    private fun m(sport: String, comp: String, home: String, away: String = "") = Match("09/11/2026", "20:00", comp, home, away, emptyList(), sport)

    @Test
    fun ligas() {
        assertTrue(Follow.followsLeague(leagues, m("Fútbol", "LaLiga Hypermotion", "Sabadell", "Real Sociedad B")))
        assertTrue(Follow.followsLeague(leagues, m("Fútbol", "LaLiga EA Sports", "Betis", "Getafe")))
        assertTrue(Follow.followsLeague(leagues, m("Fútbol", "Copa del Rey", "Tavernes", "Valencia")))
        assertTrue(Follow.followsLeague(leagues, m("Fútbol", "UEFA Champions League", "Inter", "Arsenal")))
        assertTrue(Follow.followsLeague(leagues, m("Fútbol", "UEFA Nations League A", "Spain", "Denmark")))
        assertTrue(Follow.followsLeague(leagues, m("Fútbol", "International Friendlies", "Spain", "Japan")))
        assertTrue(Follow.followsLeague(leagues, m("Baloncesto", "Euroliga", "Baskonia", "Olympiacos")))
        assertTrue(Follow.followsLeague(leagues, m("Balonmano", "Champions League", "Barça", "Veszprem")))
        // No: otro deporte u otra competición
        assertFalse(Follow.followsLeague(leagues, m("Baloncesto", "Champions League", "Bilbao", "Alba Berlin")))
        assertFalse(Follow.followsLeague(leagues, m("Hockey hielo", "Champions League", "A", "B")))
        assertFalse(Follow.followsLeague(leagues, m("Fútbol", "Champions League (F)", "Real Madrid (F)", "Paris FC (F)")))
        assertFalse(Follow.followsLeague(leagues, m("Fútbol", "AFC Champions League", "Al Hilal", "Al Ain")))
        assertFalse(Follow.followsLeague(leagues, m("Fútbol", "Serie C - Group B", "Pineto", "Vis Pesaro")))
    }

    @Test
    fun equipos() {
        assertTrue(Follow.followsTeam(teams, m("Fútbol", "Liga", "Real Madrid", "Getafe")))
        assertTrue(Follow.followsTeam(teams, m("Fútbol", "Liga", "FC Barcelona", "Sevilla")))
        assertTrue(Follow.followsTeam(teams, m("Fútbol", "LaLiga Hypermotion", "Real Zaragoza", "Eibar")))
        assertTrue(Follow.followsTeam(teams, m("Fútbol", "Friendly", "Spain", "Japan")))
        assertTrue(Follow.followsTeam(teams, m("Fútbol", "Euro (F)", "Spain (F)", "Italy (F)")))
        assertTrue(Follow.followsTeam(teams, m("Tenis", "ATP Paris", "Shelton", "Alcaraz")))
        assertTrue(Follow.followsTeam(teams, m("Tenis", "ATP Paris", "Carlos Alcaraz", "Jannik Sinner")))
        assertTrue(Follow.followsTeam(teams, m("MMA", "UFC 320", "Ilia Topuria", "Charles Oliveira")))
        assertFalse(Follow.followsTeam(teams, m("Fútbol", "Champions League (F)", "Real Madrid (F)", "Paris FC (F)")))
        assertFalse(Follow.followsTeam(teams, m("Baloncesto", "Euroliga", "Real Madrid", "Baskonia")))
        assertFalse(Follow.followsTeam(teams, m("Fútbol", "Liga", "Real Madrid Castilla", "Algeciras")))
        assertFalse(Follow.followsTeam(teams, m("Fútbol", "Liga", "Atletico Madrid (F)", "Badalona")))
    }
}
