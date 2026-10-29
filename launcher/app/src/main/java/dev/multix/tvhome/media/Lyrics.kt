package dev.multix.tvhome.media

/**
 * Lyrics as parallel arrays of start times (ms) and lines. Plain lyrics get
 * evenly spread estimated times so they can still scroll along with the song.
 */
/** Per-word start times from enhanced LRC: character offsets into the line and their times (ms). */
class WordTimes(val offsets: IntArray, val times: LongArray)

class Lyrics(
    val times: LongArray,
    val lines: Array<String>,
    val synced: Boolean,
    val words: Array<WordTimes?> = arrayOfNulls(lines.size),
) {
    val size: Int get() = lines.size

    /** Index of the line playing at [pos], or -1 before the first line. */
    fun indexAt(pos: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= pos) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun timeOf(i: Int): Long = if (i in times.indices) times[i] else Long.MAX_VALUE

    fun line(i: Int): String? = if (i in lines.indices) lines[i] else null
}

object Lrc {
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    private fun ms(min: String, sec: String, frac: String): Long {
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + f
    }

    /** Strips word tags from [rest], remembering where each tagged word starts. */
    private fun words(rest: String, offset: Long): Pair<String, WordTimes?> {
        val sb = StringBuilder(rest.length)
        val offs = ArrayList<Int>()
        val times = ArrayList<Long>()
        var last = 0
        for (m in wordTag.findAll(rest)) {
            sb.append(rest, last, m.range.first)
            offs.add(sb.length)
            times.add((ms(m.groupValues[1], m.groupValues[2], m.groupValues[3]) - offset).coerceAtLeast(0))
            last = m.range.last + 1
        }
        sb.append(rest, last, rest.length)
        val raw = sb.toString()
        val lead = raw.length - raw.trimStart().length
        val text = raw.trim()
        if (offs.isEmpty()) return text to null
        val o = IntArray(offs.size) { (offs[it] - lead).coerceIn(0, text.length) }
        return text to WordTimes(o, times.toLongArray())
    }

    fun parse(text: String): Lyrics? {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val entries = ArrayList<Pair<Long, String>>()
        val wordData = HashMap<Long, WordTimes>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            var pos = 0
            val stamps = ArrayList<Long>(2)
            while (true) {
                val m = tag.find(line, pos) ?: break
                if (m.range.first != pos) break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.toLong()
                }
                stamps.add(min * 60_000 + sec * 1000 + frac)
                pos = m.range.last + 1
            }
            if (stamps.isEmpty()) continue
            val (words, wt) = words(line.substring(pos), offset)
            for (t in stamps) {
                val at = (t - offset).coerceAtLeast(0)
                entries.add(at to words)
                if (wt != null && stamps.size == 1) wordData[at] = wt
            }
        }
        if (entries.count { it.second.isNotEmpty() } < 2) return null
        entries.sortBy { it.first }
        // An intro with no words gets an explicit empty first line so the card
        // shows the first lyric as "up next" instead of nothing.
        if (entries.first().first > 1500) entries.add(0, 0L to "")
        // Collapse runs of empty lines into one pause marker.
        val times = ArrayList<Long>(entries.size)
        val lines = ArrayList<String>(entries.size)
        val words = ArrayList<WordTimes?>(entries.size)
        for ((t, s) in entries) {
            if (s.isEmpty() && lines.isNotEmpty() && lines.last().isEmpty()) continue
            times.add(t)
            lines.add(s)
            words.add(wordData[t])
        }
        return Lyrics(times.toLongArray(), lines.toTypedArray(), true, words.toTypedArray())
    }

    fun plain(text: String, durationMs: Long): Lyrics? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val span = if (durationMs > 0) durationMs else lines.size * 4000L
        val times = LongArray(lines.size) { span * it / lines.size }
        return Lyrics(times, lines.toTypedArray(), false)
    }
}
