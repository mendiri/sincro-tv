package es.sincro.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveGuideMarkupTest {
    @Test
    fun partidoSinEscudoDeCompeticion() {
        val html = """
            <a class="article flag" href="/event/live-football-copa-del-rey-amorebieta-udlogrones/1693432"> <div class="row">
            <div class="main col-xs-2"><div class="text-center"><b>20:00</b></div><div class="text-center">
            <img data-src='https://img2.sport-tv-guide.live/images/t1.png' class='lazyload w-75' title='Fútbol' alt='Fútbol'></div></div>
            <div class="main time col-sm-2"><div class="typeName col-wrap">Fútbol</div><b>20:00</b></div>
            <div class="col-xs-6 mobile-normal pl-0 pr-0"><div class="clearfix text-nowrap">
              <div class="text-nowrap">Amorebieta - UD Logrones</div><div class="text-nowrap"><b>Copa del Rey</b></div></div></div>
            <div class="col-xs-8 mobile-small fs-13"><div><div class="text-nowrap">Amorebieta - UD Logrones</div>
              <div class="text-nowrap"><b>Copa del Rey</b></div></div></div>
            <div class="col-xs-4"><img data-src="https://img2.sport-tv-guide.live/images/tvm-station-dazn-1-fr-2960.png" title="DAZN 1" alt="DAZN 1" class="lazyload"></div>
            </div></a>
        """.trimIndent()
        val m = LiveGuide.parse(html, "10/11/2026").single()
        assertEquals("20:00", m.time)
        assertEquals("Amorebieta", m.home)
        assertEquals("UD Logrones", m.away)
        assertEquals("Copa del Rey", m.competition)
        assertEquals(listOf("2960"), m.channelIds)
    }
}
