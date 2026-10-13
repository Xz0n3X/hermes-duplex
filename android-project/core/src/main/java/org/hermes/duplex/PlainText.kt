package org.hermes.duplex

/**
 * Markdown as it should look on a watch, which is: not as markdown.
 *
 * The phone renders it properly -- `Markdown.kt` builds a Spanned with real
 * bold, real bullets and tappable links, because a phone has the pixels and
 * the toolkit for it. A watch face has neither. It is a circle of text a
 * couple of inches across, read at arm's length in one glance, and the same
 * reply arrives there as "**the fix** is in `DuplexClient.kt`" -- asterisks
 * and backticks read as literal punctuation, which is what was reported.
 *
 * So the markup is flattened rather than rendered. Emphasis becomes plain
 * words, because a glance cannot use the emphasis anyway; list markers become
 * a bullet, because that shape survives at any size; fences and headings go
 * entirely. What is left is the sentence, which is the part a wrist is for.
 *
 * Deliberately close to `Speaker.speakable`, and deliberately not shared with
 * it: that one answers "what does this sound like" and drops things a reader
 * still wants to see -- a path becomes "that file" when spoken, and stays a
 * path when read. Two questions, two answers, one set of patterns.
 */
object PlainText {

    /**
     * Where each sentence of [text] starts and ends, as (from, toExclusive).
     *
     * Used to follow the voice: the synthesiser reports its position as a
     * character offset, and an offset alone cannot be scrolled to. Cut into
     * sentences, the reply becomes a list of things a screen can move
     * between, and "where is it reading" becomes "which of these contains
     * that offset".
     *
     * Trailing whitespace is absorbed into the sentence it follows, so every
     * range starts on a real character and offsets stay exact -- a chunk
     * trimmed after the fact would shift the highlight by however many
     * spaces were removed.
     */
    fun sentences(text: String, maxChars: Int = Int.MAX_VALUE): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val here = text[index]
            val ends = here == '\n' || (
                (here == '.' || here == '!' || here == '?') &&
                    (index + 1 >= text.length || text[index + 1].isWhitespace())
                )
            if (!ends) {
                index++
                continue
            }
            var stop = index + 1
            while (stop < text.length && text[stop].isWhitespace()) stop++
            if (text.substring(start, stop).isNotBlank()) out += start to stop
            start = stop
            index = stop
        }
        if (start < text.length && text.substring(start).isNotBlank()) {
            out += start to text.length
        }
        if (maxChars == Int.MAX_VALUE) return out
        // A sentence longer than the screen is still one item, and an item
        // taller than the viewport cannot be centred on the word being read:
        // the top of it sits in the middle and the rest, including wherever
        // the voice has got to, runs off the bottom. Cut at a space so the
        // pieces are lines rather than words split in half.
        val cut = mutableListOf<Pair<Int, Int>>()
        for ((from, to) in out) {
            var at = from
            while (to - at > maxChars) {
                val limit = at + maxChars
                var brk = text.lastIndexOf(' ', limit)
                if (brk <= at) brk = limit          // one very long word
                cut += at to brk + 1
                at = brk + 1
            }
            if (at < to) cut += at to to
        }
        return cut
    }

    /** [text] with its markup removed, ready to put straight in a Text(). */
    fun flatten(text: String): String = text
        // Fences first: their contents must not be treated as markup, and the
        // language tag on the opening fence is not a word anybody wants read.
        .replace(Regex("(?m)^\\s*```[A-Za-z0-9_+-]*\\s*$"), "")
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        // Emphasis, strongest marker first so ** is not eaten as two *.
        .replace(Regex("(\\*\\*|__)(?=\\S)(.+?\\S)\\1"), "$2")
        .replace(Regex("(?<![*_\\w])([*_])(?=\\S)([^*_]+?\\S)\\1(?![*_\\w])"), "$2")
        .replace(Regex("~~(?=\\S)(.+?\\S)~~"), "$1")
        // Inline code keeps its contents: on a watch the one thing worth
        // showing from a code span is usually the name inside it.
        .replace(Regex("`([^`\n]+)`"), "$1")
        .replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s*>\\s?"), "")
        // A table is a grid, and a watch has no room for one. The rule row
        // goes, and every other row becomes its cells joined by a dash --
        // which is what a row means read aloud, and the only thing it can be
        // on a screen this narrow. Rows must start with a pipe to count, so
        // ordinary prose containing one is left alone.
        .replace(
            Regex("(?m)^[ \\t]*\\|?[ \\t:|-]*-{2,}[ \\t:|-]*\\|?[ \\t]*$"), ""
        )
        .replace(Regex("(?m)^[ \\t]*\\|(.+?)\\|?[ \\t]*$")) { row ->
            row.groupValues[1].split("|")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" — ")
        }
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        // A rule is a horizontal line drawn in punctuation. There is no room
        // for it and nothing is lost.
        .replace(Regex("(?m)^\\s*([-*_])(?:\\s*\\1){2,}\\s*$"), "")
        // Whatever markers are left are stray: an unclosed bold, a lone
        // backtick in prose. Each must still be touching a word to count --
        // a marker with space on both sides is arithmetic or a glob, not
        // markup, and "2 * 3" should not come out as "2  3".
        .replace(Regex("(?<!\\w)[*_~`]+(?=\\S)|(?<=\\S)[*_~`]+(?!\\w)"), "")
        .replace(Regex("(?m)[ \\t]+$"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
