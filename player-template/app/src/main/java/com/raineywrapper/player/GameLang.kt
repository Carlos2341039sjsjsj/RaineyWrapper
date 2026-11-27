package com.raineywrapper.player

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Detecta en que idioma esta el texto de un juego de RPG Maker MV/MZ leyendo su carpeta data.
 * Solo lee y cuenta letras y palabras comunes: NO traduce nada.
 */
object GameLang {
    class Result(val code: String, val label: String)

    private val ES = setOf("el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "por", "con", "para", "es", "se", "no", "del", "al", "lo", "como", "pero", "mas", "esta", "tu", "te", "me", "mi")
    private val EN = setOf("the", "and", "you", "to", "is", "of", "in", "it", "that", "was", "for", "are", "with", "this", "have", "but", "not", "your", "what", "can", "will", "do")

    fun detect(root: File): Result? {
        try {
            val dir = listOf(File(root, "data"), File(root, "www/data")).firstOrNull { it.isDirectory } ?: return null
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.length() < 6_000_000L } ?: return null
            val order = files.sortedWith(compareBy({ !(it.name.startsWith("Map") || it.name == "CommonEvents.json") }, { it.name }))
            val strings = ArrayList<String>()
            val budget = intArrayOf(150_000)
            for (f in order) {
                if (budget[0] <= 0) break
                val text = f.readText(Charsets.UTF_8)
                val node: Any = try {
                    if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
                } catch (e: Throwable) {
                    continue
                }
                collect(node, strings, budget)
            }
            var kana = 0
            var han = 0
            var hangul = 0
            var cyr = 0
            var latin = 0
            var es = 0
            var en = 0
            for (s in strings) {
                for (ch in s) {
                    if (ch in '぀'..'ヿ') kana++
                    else if (ch in '一'..'鿿') han++
                    else if (ch in '가'..'힯') hangul++
                    else if (ch in 'Ѐ'..'ӿ') cyr++
                    else if (ch.isLetter() && ch.code < 0x250) latin++
                    if (ch == 'ñ' || ch == '¿' || ch == '¡') es += 3
                }
                if (s.contains(' ')) {
                    for (w in s.lowercase().split(Regex("[^\\p{L}]+"))) {
                        if (w in ES) es++
                        if (w in EN) en++
                    }
                }
            }
            val total = kana + han + hangul + cyr + latin
            if (total < 30) return null
            return when {
                kana * 10 >= total -> Result("ja", "japones")
                hangul * 5 >= total -> Result("ko", "coreano")
                han * 5 >= total -> Result("zh", "chino")
                cyr * 3 >= total -> Result("ru", "ruso")
                es + en < 15 -> Result("other", "otro idioma (alfabeto latino)")
                es > en -> Result("es", "espanol")
                else -> Result("en", "ingles")
            }
        } catch (e: Throwable) {
            return null
        }
    }

    // Solo textos que parecen frases: con espacios o con letras no ASCII (se saltan nombres de archivo y codigos).
    private fun collect(node: Any?, out: MutableList<String>, budget: IntArray) {
        if (budget[0] <= 0) return
        when (node) {
            is String -> {
                if (node.length >= 4 && (node.contains(' ') || node.any { it.code > 127 })) {
                    out.add(node)
                    budget[0] -= node.length
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (budget[0] <= 0) return
                    collect(node.opt(i), out, budget)
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    if (budget[0] <= 0) return
                    collect(node.opt(keys.next()), out, budget)
                }
            }
        }
    }
}
