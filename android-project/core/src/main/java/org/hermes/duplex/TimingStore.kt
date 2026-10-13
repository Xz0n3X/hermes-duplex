package org.hermes.duplex

import org.json.JSONObject

/**
 * The last few turns, with where their time went.
 *
 * Fed by the server's `trajectory` event, which carries the same numbers the
 * server logs. Kept in memory only: this is for looking at now, not an audit
 * trail.
 */
object TimingStore {

    data class Stage(val name: String, val seconds: Double)

    data class Turn(
        val at: Long,
        val label: String,
        val elapsed: Double,
        val firstToken: Double?,
        val stages: List<Stage>,
    ) {
        /** Latency you actually waited, before any audio existed. */
        val thinking: Double get() = firstToken ?: elapsed

        fun stage(name: String): Double =
            stages.firstOrNull { it.name == name }?.seconds ?: 0.0
    }

    private const val CAPACITY = 25
    private val turns = ArrayDeque<Turn>()

    @Synchronized
    fun add(event: JSONObject) {
        val stages = mutableListOf<Stage>()
        val array = event.optJSONArray("stages")
        if (array != null) {
            for (i in 0 until array.length()) {
                val s = array.getJSONObject(i)
                stages += Stage(s.getString("name"), s.optDouble("seconds", 0.0))
            }
        }
        val first = if (event.isNull("first_token")) null else event.optDouble("first_token")
        while (turns.size >= CAPACITY) turns.removeFirst()
        turns.addLast(
            Turn(System.currentTimeMillis(), event.optString("label"),
                 event.optDouble("elapsed", 0.0), first, stages)
        )
    }

    @Synchronized
    fun recent(): List<Turn> = turns.toList().reversed()

    @Synchronized
    fun clear() = turns.clear()

    /** One short line to sit under a reply bubble. */
    fun caption(turn: Turn): String {
        val ttf = turn.firstToken?.let { " \u00b7 first word ${"%.1f".format(it)}s" } ?: ""
        return "${"%.1f".format(turn.elapsed)}s$ttf"
    }

    /** A compact line for the main screen. */
    fun oneLine(turn: Turn): String {
        val bits = turn.stages
            .filter { it.seconds >= 0.01 }
            .joinToString("  ") { "${it.name} ${"%.1f".format(it.seconds)}s" }
        val ttf = turn.firstToken?.let { "  ·  first word ${"%.1f".format(it)}s" } ?: ""
        return "${"%.1f".format(turn.elapsed)}s total$ttf\n$bits"
    }
}
