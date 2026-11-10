package es.sincro.tv

import java.text.Normalizer

/**
 * Detectar el país de un canal. Se usa el código de país de Live Guide ("16" = España, "2" = Reino Unido…).
 * - Tu lista: prefijo del nombre ("ES|", "[UK]", "IT:", "|PT|") o su categoría ("ESPAÑA | DEPORTES").
 * - Live Guide: la lista del país de donde viene el canal, o lo que diga su nombre ("DAZN España", "DAZN [USA]").
 */
object Countries {
    /** id de Live Guide -> códigos cortos (2-3 letras) y nombres completos. */
    private val TABLE: Map<String, Pair<List<String>, List<String>>> = mapOf(
        "16" to (listOf("es", "esp", "spa") to listOf("spain", "espana", "spanish", "espanol")),
        "2" to (listOf("uk", "gb", "gbr", "eng") to listOf("united kingdom", "reino unido", "england", "british", "britain")),
        "19" to (listOf("pt", "por", "prt") to listOf("portugal", "portuguese", "portugues")),
        "17" to (listOf("it", "ita") to listOf("italy", "italia", "italian", "italiano")),
        "4" to (listOf("de", "ger", "deu") to listOf("germany", "deutschland", "alemania", "german", "deutsch")),
        "18" to (listOf("fr", "fra") to listOf("france", "francia", "french", "francais")),
        "10" to (listOf("nl", "ned", "nld") to listOf("netherlands", "nederland", "holland", "holanda", "paises bajos", "dutch")),
        "33" to (listOf("be", "bel") to listOf("belgium", "belgique", "belgie", "belgica")),
        "6" to (listOf("ch", "sui", "che") to listOf("switzerland", "suiza", "schweiz", "suisse", "svizzera")),
        "5" to (listOf("at", "aut") to listOf("austria", "osterreich")),
        "15" to (listOf("ie", "irl") to listOf("ireland", "irlanda")),
        "76" to (listOf("lu", "lux") to listOf("luxembourg", "luxemburgo", "luxemburg")),
        "21" to (listOf("se", "swe") to listOf("sweden", "sverige", "suecia", "swedish")),
        "22" to (listOf("no", "nor") to listOf("norway", "norge", "noruega", "norwegian")),
        "28" to (listOf("dk", "den", "dnk") to listOf("denmark", "danmark", "dinamarca", "danish")),
        "20" to (listOf("fi", "fin") to listOf("finland", "suomi", "finlandia", "finnish")),
        "72" to (listOf("is", "isl") to listOf("iceland", "islandia", "island")),
        "30" to (listOf("pl", "pol") to listOf("poland", "polska", "polonia", "polish")),
        "31" to (listOf("cz", "cze") to listOf("czech", "cesko", "chequia", "czechia")),
        "32" to (listOf("sk", "svk") to listOf("slovakia", "eslovaquia", "slovensko")),
        "34" to (listOf("hu", "hun") to listOf("hungary", "hungria", "magyar")),
        "42" to (listOf("ro", "rou", "rom") to listOf("romania", "rumania")),
        "50" to (listOf("bg", "bul", "bgr") to listOf("bulgaria")),
        "24" to (listOf("hr", "cro", "hrv") to listOf("croatia", "hrvatska", "croacia")),
        "23" to (listOf("si", "slo", "svn") to listOf("slovenia", "eslovenia", "slovenija")),
        "25" to (listOf("rs", "srb") to listOf("serbia", "srbija")),
        "38" to (listOf("ba", "bih") to listOf("bosnia")),
        "36" to (listOf("al", "alb") to listOf("albania", "shqip")),
        "26" to (listOf("gr", "gre", "grc") to listOf("greece", "grecia", "hellas", "greek")),
        "27" to (listOf("cy", "cyp") to listOf("cyprus", "chipre")),
        "73" to (listOf("mt", "mlt") to listOf("malta")),
        "35" to (listOf("tr", "tur") to listOf("turkey", "turkiye", "turquia", "turk", "turkish")),
        "37" to (listOf("ru", "rus") to listOf("russia", "rusia", "russian")),
        "41" to (listOf("ua", "ukr") to listOf("ukraine", "ucrania")),
        "47" to (listOf("by", "blr") to listOf("belarus", "bielorrusia")),
        "46" to (listOf("ee", "est") to listOf("estonia")),
        "40" to (listOf("lv", "lva", "lat") to listOf("latvia", "letonia")),
        "45" to (listOf("lt", "ltu") to listOf("lithuania", "lituania")),
        "74" to (listOf("ge", "geo") to listOf("georgia")),
        "59" to (listOf("az", "aze") to listOf("azerbaijan", "azerbaiyan")),
        "71" to (listOf("kz", "kaz") to listOf("kazakhstan", "kazajistan")),
        "1" to (listOf("us", "usa") to listOf("united states", "estados unidos", "america")),
        "7" to (listOf("ca", "can") to listOf("canada")),
        "44" to (listOf("mx", "mex") to listOf("mexico")),
        "75" to (listOf("do", "dom") to listOf("dominicana", "dominican")),
        "67" to (listOf("gt", "gua") to listOf("guatemala")),
        "69" to (listOf("sv", "slv") to listOf("el salvador")),
        "68" to (listOf("hn", "hon") to listOf("honduras")),
        "81" to (listOf("ni", "nic") to listOf("nicaragua")),
        "79" to (listOf("cr", "crc") to listOf("costa rica")),
        "83" to (listOf("pa", "pan") to listOf("panama")),
        "62" to (listOf("co", "col") to listOf("colombia")),
        "65" to (listOf("ve", "ven") to listOf("venezuela")),
        "80" to (listOf("ec", "ecu") to listOf("ecuador")),
        "63" to (listOf("pe", "per") to listOf("peru")),
        "78" to (listOf("bo", "bol") to listOf("bolivia")),
        "64" to (listOf("cl", "chi", "chl") to listOf("chile")),
        "61" to (listOf("ar", "arg") to listOf("argentina")),
        "66" to (listOf("py", "par", "pry") to listOf("paraguay")),
        "82" to (listOf("uy", "uru", "ury") to listOf("uruguay")),
        "60" to (listOf("br", "bra") to listOf("brasil", "brazil")),
        "58" to (listOf("ma", "mar") to listOf("morocco", "maroc", "marruecos")),
        "85" to (listOf("eg", "egy") to listOf("egypt", "egipto")),
        "43" to (listOf("ng", "nga") to listOf("nigeria")),
        "57" to (listOf("ke", "ken") to listOf("kenya")),
        "14" to (listOf("za", "rsa", "zaf") to listOf("south africa", "sudafrica")),
        "48" to (listOf("il", "isr") to listOf("israel")),
        "39" to (listOf("qa", "qat") to listOf("qatar")),
        "12" to (listOf("sa", "ksa", "sau") to listOf("saudi", "arabia saudi")),
        "70" to (listOf("pk", "pak") to listOf("pakistan")),
        "9" to (listOf("in", "ind") to listOf("india")),
        "84" to (listOf("cn", "chn") to listOf("china")),
        "52" to (listOf("hk", "hkg") to listOf("hong kong")),
        "56" to (listOf("mo", "mac") to listOf("macao", "macau")),
        "54" to (listOf("tw", "twn") to listOf("taiwan")),
        "77" to (listOf("jp", "jpn") to listOf("japan", "japon")),
        "53" to (listOf("th", "tha") to listOf("thailand", "tailandia")),
        "55" to (listOf("vn", "vie", "vnm") to listOf("vietnam")),
        "13" to (listOf("mm", "mmr") to listOf("myanmar")),
        "11" to (listOf("my", "mys") to listOf("malaysia", "malasia")),
        "8" to (listOf("sg", "sgp") to listOf("singapore", "singapur")),
        "51" to (listOf("id", "idn") to listOf("indonesia")),
        "49" to (listOf("ph", "phi", "phl") to listOf("philippines", "filipinas")),
        "3" to (listOf("au", "aus") to listOf("australia")),
        "29" to (listOf("nz", "nzl") to listOf("new zealand", "nueva zelanda")),
    )

    private val CODE = HashMap<String, String>().also { m -> TABLE.forEach { (id, v) -> v.first.forEach { m[it] = id } } }
    private val FULL: List<Pair<Regex, String>> = TABLE.flatMap { (id, v) -> v.second.map { Regex("\\b" + Regex.escape(it) + "\\b") to id } }

    private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Código al principio: "ES|", "ES:", "ES -", "[ES]", "|ES|", "(ES)", "ES ▎". */
    private val PREFIX = Regex("^[\\s|\\[(#*]*([a-z]{2,3})\\s*(?:[|\\]):\\-–_▎•]|\\s{2})")
    /** Código al final: "DAZN [USA]", "Amazon Prime ES", "TVE (ES)". */
    private val SUFFIX = Regex("(?:\\[|\\(|\\s)([A-Za-z]{2,3})[\\])]?\\s*$")

    private fun byPrefix(s: String): String? = PREFIX.find(plain(s))?.groupValues?.get(1)?.let { CODE[it] }
    private fun byFullName(s: String): String? { val p = plain(s); return FULL.firstOrNull { it.first.containsMatchIn(p) }?.second }

    // ---------- Prefijos de tu proveedor IPTV (categorias_iptv.csv) ----------

    private val LATAM = setOf("61", "64", "62", "79", "80", "67", "68", "69", "44", "81", "83", "63", "75", "82", "65", "66", "78")
    private val MENA = setOf("39", "12", "85", "58")

    /** Prefijo de la categoría -> países de Live Guide. Algunos no son el código ISO (AR = árabe, IS = Israel, SK = Corea…). */
    private val PROVIDER: Map<String, Set<String>?> = mapOf(
        "es" to setOf("16"), "uk" to setOf("2"), "pt" to setOf("19"), "it" to setOf("17"), "de" to setOf("4"), "fr" to setOf("18"),
        "nl" to setOf("10"), "be" to setOf("33"), "ch" to setOf("6"), "at" to setOf("5"), "ir" to setOf("15"), "pl" to setOf("30"),
        "gr" to setOf("26"), "cy" to setOf("27"), "mt" to setOf("73"), "al" to setOf("36"), "sl" to setOf("23"), "bh" to setOf("38"),
        "rs" to setOf("25"), "hr" to setOf("24"), "exyu" to setOf("24", "25", "38", "23"), "lt" to setOf("45"), "la" to setOf("40"),
        "est" to setOf("46"), "hu" to setOf("34"), "cz" to setOf("31"), "ro" to setOf("42"), "bg" to setOf("50"), "ru" to setOf("37"),
        "by" to setOf("47"), "ukr" to setOf("41"), "ge" to setOf("74"), "ka" to setOf("71"), "az" to setOf("59"), "au" to setOf("3"),
        "nz" to setOf("29"), "br" to setOf("60"), "us" to setOf("1"), "ca" to setOf("7"), "na" to setOf("1", "7"), "tr" to setOf("35"),
        "is" to setOf("48"), "sg" to setOf("8"), "hk" to setOf("52"), "id" to setOf("51"), "th" to setOf("53"), "tai" to setOf("54"),
        "vt" to setOf("55"), "my" to setOf("11"), "jp" to setOf("77"), "ph" to setOf("49"), "fi" to setOf("20"),
        "lv" to setOf("40"), "mxc" to setOf("44"), "arg" to setOf("61"), "mx" to setOf("44"), "se" to setOf("21"),
        "dk" to setOf("28"), "no" to setOf("22"), "ie" to setOf("15"), "lu" to setOf("76"),
        "afr" to setOf("14", "43", "57"), "asia" to setOf("9", "70"), "crb" to setOf("75"),
        "ar" to MENA, "lat" to LATAM, "eu" to null,
        // Sin país de Live Guide (plataformas varias, Corea, Kosovo, Macedonia, Montenegro, Armenia…)
        "bee" to null, "sk" to null, "ko" to null, "mk" to null, "cg" to null, "am" to null, "uz" to null, "tj" to null,
        "ku" to null, "afg" to null, "ban" to null, "np" to null, "su" to null, "wc" to null, "mu" to null,
    )

    /** Nombres que afinan dentro de un prefijo ("AR| MOROCCO", "LAT| MEXICO", "EU| LUXEMBOURG", "CH| CHINA"). */
    private val REFINE: List<Pair<Regex, Set<String>?>> = listOf(
        "morocco|maroc" to setOf("58"), "egypt|egypte" to setOf("85"), "qatar" to setOf("39"), "saudi|saudia" to setOf("12"),
        "argentina|\\barg\\b" to setOf("61"), "chile" to setOf("64"), "colombia" to setOf("62"), "costa rica" to setOf("79"),
        "ecuador" to setOf("80"), "guatemala" to setOf("67"), "honduras" to setOf("68"), "el salvador" to setOf("69"),
        "mexico" to setOf("44"), "nicaragua" to setOf("81"), "panama" to setOf("83"), "peru" to setOf("63"),
        "dominicana" to setOf("75"), "uruguay" to setOf("82"), "venezuela" to setOf("65"), "puerto rico" to null,
        "netherlands" to setOf("10"), "luxembourg" to setOf("76"),
        "china|hongkong|hong kong" to setOf("84", "52"), "iran" to null, "horse racing" to null,
    ).map { Regex("\\b(?:" + it.first + ")") to it.second }

    /** Sin prefijo: el país va delante ("SWEDEN HD & HEVC", "DENMARK SPORT…"). */
    private val LEADING = mapOf("sweden" to "21", "denmark" to "28", "norway" to "22", "finland" to "20", "iceland" to "72")

    private val NO_COUNTRY = emptySet<String>()

    /**
     * País (o países, para grupos regionales) de una categoría o nombre con el formato de tu proveedor.
     * Devuelve un conjunto vacío si es temático o de un país que Live Guide no tiene, y null si no lo reconoce.
     */
    fun ofProviderLabel(label: String): Set<String>? {
        val p = plain(label).trim()
        val bar = p.indexOf('|')
        if (bar in 1..6) {
            // "PL-VIP|", "UK-NOWTV|", "ES-C|": cuenta lo de antes del guion
            val code = p.substring(0, bar).trim().trim('[', '(', ' ').substringBefore('-').trim()
            val rest = p.substring(bar + 1)
            if (!PROVIDER.containsKey(code)) return null
            for ((re, set) in REFINE) if (re.containsMatchIn(rest)) return set ?: NO_COUNTRY
            return PROVIDER[code] ?: NO_COUNTRY
        }
        val first = p.split(Regex("[^a-z]+")).firstOrNull { it.isNotBlank() } ?: return null
        LEADING[first]?.let { return setOf(it) }
        return null
    }

    /** Prefijos que en tu lista son zonas o se repiten con otro sentido: ahí manda la categoría. */
    private val ZONE_CODES = setOf("ar", "lat", "ir", "hr", "ch", "eu", "asia", "afr", "na", "crb", "exyu")

    private fun codeOf(label: String): String? {
        val p = plain(label).trim()
        val bar = p.indexOf('|')
        return if (bar in 1..10) p.substring(0, bar).trim().trim('[', '(', ' ').substringBefore('-').trim() else null
    }

    /**
     * País(es) de un canal de tu lista. Si el nombre lleva un código de país ("FR| DAZN 1", "PL| ELEVEN SPORTS 2"),
     * manda el nombre; si no, la categoría. En los prefijos de zona (AR|, LAT|…) manda la categoría, que es más exacta.
     */
    fun ofChannelSet(name: String, group: String): Set<String>? {
        val byName = ofProviderLabel(name)
        if (byName != null && byName.isNotEmpty() && codeOf(name) !in ZONE_CODES) return byName
        ofProviderLabel(group)?.let { if (it.isNotEmpty() || byName == null) return it }
        byName?.let { return it }
        return (byPrefix(name) ?: byPrefix(group) ?: byFullName(group) ?: byFullName(name))?.let { setOf(it) }
    }

    /** ¿Este canal de tu lista es del país [id]? null = no se sabe. */
    fun channelIs(name: String, group: String, id: String): Boolean? {
        val s = ofChannelSet(name, group) ?: return null
        return id in s
    }

    /** Códigos que, sueltos en el nombre, indican claramente un país (sin palabras comunes como "IN", "IT", "NO"…). */
    private val MARKERS = mapOf(
        "es" to "16", "esp" to "16", "uk" to "2", "gb" to "2", "fr" to "18", "fra" to "18", "pt" to "19", "ger" to "4", "deu" to "4",
        "ita" to "17", "pl" to "30", "tr" to "35", "mx" to "44", "usa" to "1", "us" to "1",
        "arg" to "61", "swe" to "21", "nor" to "22", "dnk" to "28", "fin" to "20", "ru" to "37", "gr" to "26", "ro" to "42",
    )

    /**
     * ¿El nombre del canal menciona otro país distinto de [want]? ("IT| DAZN 2 ES HD" es la señal española, no la italiana).
     * No cuenta el prefijo de antes de la barra.
     */
    fun mentionsOtherCountry(name: String, want: String, stationName: String = ""): Boolean {
        val rest = plain(name).replace(Regex("^[^|]{1,10}\\|"), " ")
        val own = plain(stationName)
        val words = rest.split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        for (w in words) MARKERS[w]?.let { if (it != want && !Regex("\\b$w\\b").containsMatchIn(own)) return true }
        for ((re, id) in FULL) if (id != want && re.containsMatchIn(rest) && !re.containsMatchIn(own)) return true
        return false
    }

    /** País de un canal de tu lista (el primero si es un grupo regional). */
    fun ofChannel(name: String, group: String): String? = ofChannelSet(name, group)?.firstOrNull()

    /** País que dice el nombre de un canal de Live Guide (solo si lo dice claro). */
    fun ofStationName(name: String): String? {
        byFullName(name)?.let { return it }
        val m = SUFFIX.find(name.trim()) ?: return null
        val code = m.groupValues[1]
        // Al final del nombre solo vale si está en mayúsculas o entre corchetes ("Prime ES", "[USA]"), para no confundir palabras
        if (code != code.uppercase() && !m.value.contains('[') && !m.value.contains('(')) return null
        return CODE[code.lowercase()]
    }

    /** id del país a partir del nombre en castellano que usa la app ("España"). */
    fun idOfName(name: String): String? = LiveGuide.COUNTRIES.firstOrNull { it.second == name }?.first
}
