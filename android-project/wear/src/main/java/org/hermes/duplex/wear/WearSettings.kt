package org.hermes.duplex.wear

import android.content.Context
import org.hermes.duplex.OrbModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the watch reaches the bridge, and where it is.
 *
 * Two routes, because they fail in opposite situations. Through the phone
 * costs nothing and works anywhere the two are paired, but it is useless
 * without the phone -- on a run, or with it flat in another room, which is
 * when a watch is most of what you have. Straight over Wi-Fi needs the watch
 * on a network that can see the bridge, and then needs nothing else.
 *
 * Kept on the watch rather than pushed from the phone: the whole point of the
 * Wi-Fi route is to work when the phone cannot tell it anything.
 */
class WearSettings(context: Context) {

    enum class Route { PHONE, WIFI }

    private val prefs = context.getSharedPreferences("s1lv3r-wear", Context.MODE_PRIVATE)

    var route by mutableStateOf(
        runCatching { Route.valueOf(prefs.getString(KEY_ROUTE, null) ?: "PHONE") }
            .getOrDefault(Route.PHONE)
    )
        private set

    var bridgeUrl by mutableStateOf(prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL)
        private set

    /**
     * How loud it speaks, 0.1 to 1.
     *
     * Kept here rather than left to the watch's media volume, and remembered,
     * because it is a thing you set once about this app -- not something to
     * rediscover every time you open it.
     */
    var volume by mutableStateOf(prefs.getFloat(KEY_VOLUME, 1f))
        private set

    /**
     * Whether a reply that lands is read out on the wrist.
     *
     * A switch rather than always-on, because the two ways a watch is used
     * pull opposite ways: monitoring an agent from across the room wants
     * every block spoken, and the same watch in a meeting wants the buzz and
     * nothing else. It was effectively always-on before, which is the wrong
     * default to have no escape from.
     *
     * Off by default. A watch that starts talking the first time it is paired
     * is a watch people turn off entirely.
     */
    var speakReplies by mutableStateOf(prefs.getBoolean(KEY_SPEAK, false))
        private set

    /**
     * The colour of each orb state, chosen rather than derived.
     *
     * I argued for a single rotation of the whole wheel, on the grounds that
     * the orb says what it is doing by its hue and that setting every state
     * the same throws that away. Overruled, and reasonably: the states are
     * still told apart by how the orb *moves* -- listening breathes, thinking
     * drifts, speaking beats once per word -- so colour is not the only
     * signal, and it is the one worth being able to pick.
     *
     * `CYCLE` is a hue nothing can be, standing for "sweep the whole wheel".
     * A separate boolean per state would have meant two keys and two controls
     * for one decision.
     */
    fun hueOf(state: OrbModel.State): Float =
        prefs.getFloat(hueKey(state), defaultHue(state))

    fun setHue(state: OrbModel.State, next: Float) {
        val wrapped = if (next >= CYCLE) CYCLE else ((next % 360f) + 360f) % 360f
        prefs.edit().putFloat(hueKey(state), wrapped).apply()
        revision++
    }

    /** Bumped so Compose re-reads the prefs above, which it cannot observe. */
    var revision by mutableStateOf(0)
        private set

    fun setSpeaking(on: Boolean) {
        speakReplies = on
        prefs.edit().putBoolean(KEY_SPEAK, on).apply()
    }

    fun setLoudness(next: Float) {
        val clamped = next.coerceIn(0.1f, 1f)
        volume = clamped
        prefs.edit().putFloat(KEY_VOLUME, clamped).apply()
    }

    fun choose(next: Route) {
        route = next
        prefs.edit().putString(KEY_ROUTE, next.name).apply()
    }

    fun rememberBridge(url: String) {
        val cleaned = normalise(url)
        if (cleaned.isBlank()) return
        bridgeUrl = cleaned
        prefs.edit().putString(KEY_URL, cleaned).apply()
    }

    /**
     * Make something typed on a watch into a URL.
     *
     * Dictating an address gives "192.168.1.48" at best and a sentence at
     * worst, so the scheme and the path are added rather than demanded --
     * nobody should have to say "w s colon slash slash" to a watch.
     */
    private fun normalise(raw: String): String {
        var text = raw.trim().replace(" ", "")
        if (text.isEmpty()) return ""
        if (!text.startsWith("ws://") && !text.startsWith("wss://")) {
            text = "ws://$text"
        }
        if (!text.removePrefix("ws://").removePrefix("wss://").contains(":")) {
            text = "$text:8770"
        }
        if (!text.endsWith("/ws")) {
            text = text.trimEnd('/') + "/ws"
        }
        return text
    }

    companion object {
        const val KEY_ROUTE = "route"
        const val KEY_URL = "bridge_url"
        const val KEY_VOLUME = "volume"
        const val KEY_SPEAK = "speak_replies"
        const val KEY_HUE = "orb_hue"

        /** Past the end of the wheel: "cycle every colour" rather than one. */
        const val CYCLE = 360f

        fun hueKey(state: OrbModel.State) = "orb_hue_${state.name.lowercase()}"

        /** What each state looks like today, so nothing changes until asked. */
        fun defaultHue(state: OrbModel.State): Float = when (state) {
            OrbModel.State.LISTENING -> 205f
            OrbModel.State.THINKING -> 200f
            OrbModel.State.WORKING -> 55f
            OrbModel.State.SPEAKING -> 150f
            OrbModel.State.HELD -> 155f
            OrbModel.State.IDLE -> 210f
        }

        /** The bridge's tailnet address, which is where it binds today. */
        const val DEFAULT_URL = "ws://100.105.180.73:8770/ws"
    }
}
