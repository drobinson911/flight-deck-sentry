package com.uasflightdeck.sentry.core

/**
 * Turns the speech text the engine already produces into a sequence of bundled voice clips (v0.3.5), for
 * controllers with no text-to-speech engine (the DJI RC Plus). Deterministic and pure, so every sentence
 * Sentry can say is unit-tested against the real clip bank.
 *
 * Output tokens are clip ids plus two pause markers, [PAUSE_SHORT] (",") and [PAUSE_LONG] (".").
 *
 * Rules, in order:
 *  - "Traffic inside|entering <zone>, …": a TFR is "T F R" + its digits; a geofence or controller cylinder whose
 *    name is not entirely in the bank becomes "geofence" / "protected area" (a free-typed name can't be voiced).
 *  - Greedy longest match against the bank's phrases (commas inside a phrase are allowed), so frequent whole
 *    sentences ("Drone position lost.") come out as one natural clip.
 *  - "1,500" -> "one thousand" "five hundred" (whole-chunk clips); "3.0" -> three point zero; integers after "TFR" are read digit by digit.
 *  - A single letter is its letter name (spelled callsigns: "N 3 8 8 K M").
 *  - Anything else (a drone name like "DEMO-1 Pilot") is spelled letter by letter and listed in [Result.spelled].
 */
class VoiceGrammar(manifest: Map<String, String>) {
    /** Clip ids in the bank. */
    val ids: Set<String> = manifest.keys
    private val phrases: Map<List<String>, String> = manifest.entries
        .filter { it.value.isNotBlank() }
        .associate { (id, match) -> match.lowercase().trim().split(Regex("\\s+")) to id }
    private val maxLen = phrases.keys.maxOfOrNull { it.size } ?: 1

    data class Result(
        val tokens: List<String>,
        /** Words that were not in the bank and were spelled out instead. */
        val spelled: List<String>,
        /** Clip ids the rules wanted but the bank lacks (must always be empty; tested). */
        val missing: List<String>,
    ) {
        val clips get() = tokens.filter { it != PAUSE_SHORT && it != PAUSE_LONG }
    }

    private enum class K { WORD, NUM, DEC, PUNCT }
    private data class Lex(val k: K, val s: String)

    fun tokenize(speech: String): Result {
        val out = ArrayList<String>()
        val spelled = ArrayList<String>()
        val missing = ArrayList<String>()
        fun emit(id: String) { if (id in ids) out += id else missing += id }
        fun pause(p: String) {
            if (out.isEmpty()) return
            val last = out.last()
            if (last == PAUSE_LONG) return
            if (last == PAUSE_SHORT) out[out.size - 1] = p else out += p
        }
        fun digit(c: Char) = emit("n${c - '0'}")
        fun spell(w: String) { spelled += w; w.lowercase().forEach { c -> if (c.isDigit()) digit(c) else if (c in 'a'..'z') emit("l_$c") } }

        val lex = lex(rewriteZone(speech))
        var i = 0
        var tfrDigits = false
        while (i < lex.size) {
            val l = lex[i]
            if (l.k == K.PUNCT) { pause(if (l.s == ",") PAUSE_SHORT else PAUSE_LONG); if (l.s != ",") tfrDigits = false; i++; continue }
            // longest phrase first (commas between the phrase's words are allowed and swallowed)
            var matched = false
            if (l.k == K.WORD) for (n in minOf(maxLen, lex.size - i) downTo 1) {
                val words = ArrayList<String>(); var j = i
                while (j < lex.size && words.size < n) {
                    val x = lex[j]
                    if (x.k == K.WORD) words += x.s.lowercase()
                    else if (!(x.k == K.PUNCT && x.s == "," && words.isNotEmpty())) break
                    j++
                }
                if (words.size < n) continue
                val id = phrases[words] ?: continue
                emit(id); i = j; matched = true
                tfrDigits = id == "tfr" || id == "tfr_letters"
                break
            }
            if (matched) continue
            when (l.k) {
                K.NUM -> {
                    val n = l.s.replace(",", "").toLongOrNull()
                    if (tfrDigits || n == null || n >= 100_000) l.s.filter { it.isDigit() }.forEach(::digit)
                    else number(n).forEach(::emit)
                }
                K.DEC -> {
                    val (a, b) = l.s.split('.')
                    number(a.toLong()).forEach(::emit); emit("point"); b.forEach(::digit)
                }
                K.WORD -> if (l.s.length == 1 && l.s[0].isLetter()) emit("l_${l.s.lowercase()}") else spell(l.s)
                K.PUNCT -> Unit
            }
            i++
        }
        while (out.isNotEmpty() && (out.last() == PAUSE_SHORT || out.last() == PAUSE_LONG)) out.removeAt(out.size - 1)
        return Result(out, spelled, missing)
    }

    /**
     * Integer as clip ids: 0-99 are single clips, "N hundred" (h1-h9) and "N thousand" (t1-t30) are single clips,
     * so 1,500 is two clips ("one thousand", "five hundred"); larger thousands are composed with "thousand".
     */
    fun number(n: Long): List<String> = when {
        n < 100 -> listOf("n$n")
        n < 1000 -> listOf("h${n / 100}") + (if (n % 100 > 0) number(n % 100) else emptyList())
        else -> (if (n / 1000 <= 30) listOf("t${n / 1000}") else number(n / 1000) + "thousand") +
            (if (n % 1000 > 0) number(n % 1000) else emptyList())
    }

    private fun lex(s: String): List<Lex> = TOKEN.findAll(s).map { m ->
        val v = m.value
        when {
            m.groups[1] != null -> Lex(K.NUM, v)
            m.groups[2] != null -> Lex(K.DEC, v)
            m.groups[3] != null -> Lex(K.NUM, v)
            m.groups[4] != null -> Lex(K.WORD, v)
            else -> Lex(K.PUNCT, v)
        }
    }.toList()

    /** Free-typed zone names that the bank can't voice are replaced by their kind. */
    private fun rewriteZone(s: String): String {
        val m = ZONE.find(s) ?: return s
        val zone = m.groupValues[2]
        val spoken = when {
            zone.startsWith("TFR ") -> zone
            zone.startsWith("geofence ") -> if (voiceable(zone)) zone else "geofence"
            else -> if (voiceable(zone)) zone else "protected area"
        }
        return s.replaceRange(m.groups[2]!!.range, spoken)
    }

    private fun voiceable(z: String): Boolean {
        val words = z.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }
        var i = 0
        outer@ while (i < words.size) {
            for (n in minOf(maxLen, words.size - i) downTo 1) if (phrases[words.subList(i, i + n)] != null) { i += n; continue@outer }
            return false
        }
        return words.isNotEmpty()
    }

    companion object {
        const val PAUSE_SHORT = ","
        const val PAUSE_LONG = "."
        private val TOKEN = Regex("""(\d{1,3}(?:,\d{3})+)|(\d+\.\d+)|(\d+)|([A-Za-z0-9']+)|([,.;:!?])""")
        private val ZONE = Regex("""^(Traffic (?:inside|entering) )(.+?), """)

        /** Ids the rules above build by name; the bank must have every one. */
        val RULE_IDS: List<String> = (0..99).map { "n$it" } + (1..9).map { "h$it" } + (1..30).map { "t$it" } +
            listOf("hundred", "thousand", "point") + ('a'..'z').map { "l_$it" }

        /** Parses manifest.tsv (id TAB match words; '#' comments). */
        fun parseManifest(text: String): Map<String, String> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split('\t').let { it[0].trim() to (it.getOrNull(1)?.trim() ?: "") } }
    }
}
