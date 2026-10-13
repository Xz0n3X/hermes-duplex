package org.hermes.duplex

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * A small markdown renderer, ported from AgentKit's `web/js/markdown.js`.
 *
 * Covers what this agent actually emits: fenced and inline code, headings,
 * bullets, emphasis, strikethrough, and links — including the bare `https://`
 * a model writes when it cites a source without being asked for markdown.
 * Not a full CommonMark implementation and it does not need to be; the
 * alternative is a parser dependency for constructs that never arrive.
 *
 * The system prompt tells the model never to use markdown, because replies
 * are spoken. It uses it anyway — `*3 Body Problem*` arrived in one reply and
 * showed with its asterisks. Instructions are not a rendering strategy.
 *
 * Order matters, as it does on the web: at each position the earliest match
 * wins, and code and links are matched before emphasis, so an underscore in a
 * URL or an asterisk in a code span is never read as italics. Nothing recurses
 * into a code span or a URL, which is what makes that hold.
 */
object Markdown {

    private const val CODE_BACKGROUND = 0x22808080

    /** `[label](url)` */
    private val LINK = Regex("""\[([^\]]+)]\((https?://[^\s)]+)\)""")

    /** A bare address. The trailing-punctuation guard stops a sentence's
     *  full stop being swallowed into the link. */
    private val BARE_URL = Regex("""(?:https?://|www\.)[^\s<]*[^\s<.,;:!?)\]]""")

    private val CODE = Regex("`([^`\n]+)`")
    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?\S)\1""")
    private val ITALIC = Regex("""(?<![*_\w])([*_])(?=\S)([^*_]+?\S)\1(?![*_\w])""")
    private val STRIKE = Regex("""~~(?=\S)(.+?\S)~~""")

    private val BULLET = Regex("""^\s*[-*•]\s+""")
    private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+""")
    private val HEADING = Regex("""^\s*(#{1,6})\s+(.*)$""")

    fun render(source: String): CharSequence {
        val out = SpannableStringBuilder()
        val lines = source.split("\n")

        var index = 0
        while (index < lines.size) {
            val line = lines[index]

            // Fenced code: taken whole, so nothing inside is read as markup.
            if (line.trimStart().startsWith("```")) {
                val body = StringBuilder()
                index += 1
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    body.appendLine(lines[index])
                    index += 1
                }
                index += 1
                if (out.isNotEmpty()) out.append("\n")
                appendCode(out, body.toString().trimEnd())
                continue
            }

            if (out.isNotEmpty()) out.append("\n")

            val heading = HEADING.find(line)
            if (heading != null) {
                val start = out.length
                inline(out, heading.groupValues[2])
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                out.setSpan(RelativeSizeSpan(1.15f), start, out.length, SPAN)
                index += 1
                continue
            }

            val bullet = BULLET.find(line)
            val numbered = NUMBERED.find(line)
            when {
                bullet != null -> {
                    out.append("•  ")
                    inline(out, line.substring(bullet.value.length))
                }
                numbered != null -> {
                    out.append("${numbered.groupValues[1]}.  ")
                    inline(out, line.substring(numbered.value.length))
                }
                else -> inline(out, line)
            }
            index += 1
        }
        return out
    }

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    private fun appendCode(out: SpannableStringBuilder, code: String) {
        val start = out.length
        out.append(code)
        out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
        out.setSpan(BackgroundColorSpan(CODE_BACKGROUND), start, out.length, SPAN)
    }

    /** One line, matched left to right, earliest match wins. */
    private fun inline(out: SpannableStringBuilder, line: String) {
        var rest = line
        while (rest.isNotEmpty()) {
            val next = earliest(rest)
            if (next == null) {
                out.append(rest)
                return
            }
            out.append(rest.substring(0, next.at))
            next.emit(out)
            rest = rest.substring(next.end)
        }
    }

    private class Hit(val at: Int, val end: Int, val emit: (SpannableStringBuilder) -> Unit)

    /**
     * The first construct on the line.
     *
     * Ties go to whichever is checked first, which is why code and links come
     * before emphasis: `**` inside a code span is not bold, and an underscore
     * in a URL is not italics.
     */
    private fun earliest(text: String): Hit? {
        val hits = mutableListOf<Hit>()

        CODE.find(text)?.let { m ->
            hits += Hit(m.range.first, m.range.last + 1) { out ->
                val start = out.length
                out.append(m.groupValues[1])
                out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                out.setSpan(BackgroundColorSpan(CODE_BACKGROUND), start, out.length, SPAN)
            }
        }
        LINK.find(text)?.let { m ->
            hits += Hit(m.range.first, m.range.last + 1) { out ->
                link(out, m.groupValues[1], m.groupValues[2])
            }
        }
        BARE_URL.find(text)?.let { m ->
            hits += Hit(m.range.first, m.range.last + 1) { out ->
                val shown = m.value
                // A bare `www.` host is the same citation with the scheme
                // dropped, and it is the one form of address that is
                // otherwise not clickable.
                val href = if (shown.startsWith("www.")) "https://$shown" else shown
                link(out, shown, href)
            }
        }
        BOLD.find(text)?.let { m ->
            hits += Hit(m.range.first, m.range.last + 1) { out ->
                val start = out.length
                inline(out, m.groupValues[2])
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
            }
        }
        ITALIC.find(text)?.let { m ->
            hits += Hit(m.range.first, m.range.last + 1) { out ->
                val start = out.length
                inline(out, m.groupValues[2])
                out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SPAN)
            }
        }
        STRIKE.find(text)?.let { m ->
            hits += Hit(m.range.first, m.range.last + 1) { out ->
                val start = out.length
                inline(out, m.groupValues[1])
                out.setSpan(StrikethroughSpan(), start, out.length, SPAN)
            }
        }

        return hits.minByOrNull { it.at }
    }

    /** A URLSpan, so the TextView's own link colour and underline apply. */
    private fun link(out: SpannableStringBuilder, label: String, href: String) {
        val start = out.length
        out.append(label)
        out.setSpan(URLSpan(href), start, out.length, SPAN)
    }
}
