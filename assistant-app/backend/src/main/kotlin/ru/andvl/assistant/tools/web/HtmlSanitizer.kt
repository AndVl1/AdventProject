package ru.andvl.assistant.tools.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.springframework.stereotype.Service

@Service
class HtmlSanitizer {

    private val invisibleUnicode = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u2060\\u00AD]")
    private val tagBlockRange = 0xE0000..0xE007F

    data class Result(val text: String, val notes: List<String>)

    fun sanitize(html: String): Result {
        val doc = Jsoup.parse(html)
        val notes = mutableListOf<String>()

        val commentsRemoved = removeComments(doc)
        if (commentsRemoved > 0) notes += "removed $commentsRemoved HTML comment(s)"

        val hiddenClasses = collectHiddenClasses(doc)

        val elemRemoved = doc.body()?.let { removeHiddenElements(it, hiddenClasses) } ?: 0
        if (elemRemoved > 0) notes += "removed $elemRemoved hidden element(s)"

        // <style>/<script>/<noscript> текст не должен попадать в plain-text
        doc.select("style, script, noscript").remove()

        val raw = doc.body()?.text() ?: ""
        val (clean, stripped) = stripInvisibleUnicode(raw)
        if (stripped > 0) notes += "stripped $stripped invisible Unicode codepoint(s)"

        return Result(text = clean, notes = notes)
    }

    private fun removeComments(node: Node): Int {
        var count = 0
        val toRemove = mutableListOf<Node>()
        for (child in node.childNodes()) {
            if (child is Comment) toRemove += child else count += removeComments(child)
        }
        for (n in toRemove) {
            n.remove()
            count++
        }
        return count
    }

    private fun collectHiddenClasses(doc: Document): Set<String> {
        val css = doc.select("style").joinToString("\n") { it.data() }
        val ruleRegex = Regex("\\.([A-Za-z_-][\\w-]*)\\s*\\{([^}]*)\\}")
        val hidden = mutableSetOf<String>()
        for (m in ruleRegex.findAll(css)) {
            val cls = m.groupValues[1]
            val body = m.groupValues[2]
            if (looksHidden(body)) hidden += cls
        }
        return hidden
    }

    private fun removeHiddenElements(root: Element, hiddenClasses: Set<String>): Int {
        val toRemove = mutableListOf<Element>()
        for (el in root.allElements) {
            if (el === root) continue
            val byClass = el.classNames().any { it in hiddenClasses }
            val byStyle = el.attr("style").let { it.isNotEmpty() && looksHidden(it) }
            val byAttr = el.hasAttr("hidden") || el.attr("aria-hidden") == "true"
            if (byClass || byStyle || byAttr) toRemove += el
        }
        var n = 0
        for (el in toRemove) {
            if (el.parent() != null) {
                el.remove()
                n++
            }
        }
        return n
    }

    private fun looksHidden(rules: String): Boolean {
        val r = rules.lowercase().replace(" ", "")
        if ("display:none" in r) return true
        if ("visibility:hidden" in r) return true
        if ("opacity:0" in r && "opacity:0." !in r) return true
        if (Regex("font-size:0(\\.0+)?(px|em|rem|pt)?[;}]").containsMatchIn(r + ";")) return true
        if ("font-size:1px" in r) return true
        if ("position:absolute" in r && Regex("left:-\\d{4,}").containsMatchIn(r)) return true
        if ("position:absolute" in r && Regex("top:-\\d{4,}").containsMatchIn(r)) return true
        val color = extractColor(r, "color:")
        val bg = extractColor(r, "background:") ?: extractColor(r, "background-color:")
        if (color != null && bg != null && color == bg) return true
        return false
    }

    private fun extractColor(rules: String, prefix: String): String? {
        val idx = rules.indexOf(prefix)
        if (idx < 0) return null
        val rest = rules.substring(idx + prefix.length)
        val end = rest.indexOfAny(charArrayOf(';', '}'))
        val v = (if (end < 0) rest else rest.substring(0, end)).trim()
        return normalizeColor(v)
    }

    private fun normalizeColor(c: String): String? {
        val s = c.trim().lowercase()
        return when {
            s == "white" || s == "#fff" || s == "#ffffff" -> "ffffff"
            s == "black" || s == "#000" || s == "#000000" -> "000000"
            s.startsWith("#") -> {
                val hex = s.removePrefix("#")
                when (hex.length) {
                    3 -> hex.toCharArray().joinToString("") { "$it$it" }
                    6 -> hex
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun stripInvisibleUnicode(s: String): Pair<String, Int> {
        val noZw = invisibleUnicode.replace(s, "")
        var stripped = s.length - noZw.length
        val sb = StringBuilder(noZw.length)
        var i = 0
        while (i < noZw.length) {
            val cp = noZw.codePointAt(i)
            if (cp in tagBlockRange) {
                stripped++
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString() to stripped
    }
}
