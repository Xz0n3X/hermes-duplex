package org.hermes.duplex

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.hermes.duplex.ui.SettingsModel
import org.hermes.duplex.ui.SettingsScreen
import org.hermes.duplex.ui.theme.HermesTheme

/**
 * The tuning bench, off the conversation screen.
 *
 * Everything here is set once and then left alone: which bridge to talk to,
 * which voice answers, what a turn is allowed to do, and the three numbers
 * that decide when the microphone thinks you have finished a sentence.
 *
 * It attaches to the live client the same way the conversation screen does,
 * so the meter moves and the sliders show the server's real values. Only one
 * screen is resumed at a time, so the single callback slot on [DuplexClient]
 * is never contended.
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "hermes-duplex"
        private const val KEY_URL = "server_url"

        /**
         * The bridge to connect to.
         *
         * Falls back to the address strings.xml carries, which is what makes
         * a fresh sideload work with no typing.
         */
        fun serverUrl(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
            val fallback = context.getString(R.string.default_server_url)

            // Older installs saved the Mac's Tailscale address. The phone is on
            // the LAN and cannot route that address, while the bridge already
            // listens on all interfaces. Migrate that stale default once,
            // preserving any URL the user entered intentionally.
            if (saved?.startsWith("ws://100.105.180.73:") == true) {
                prefs.edit().putString(KEY_URL, fallback).apply()
                return fallback
            }
            return saved ?: fallback
        }

        fun saveServerUrl(context: Context, typed: String) {
            val url = typed.trim()
            if (url.isEmpty() || url == serverUrl(context)) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_URL, url).apply()
            EventLog.add("bridge url set to $url")
        }
    }

    private lateinit var model: SettingsModel
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = SettingsModel(this)
        setContent {
            HermesTheme {
                SettingsScreen(
                    model = model,
                    onBack = { finish() },
                    onRemotes = { startActivity(Intent(this, RemotesActivity::class.java)) },
                    onMoreVoices = { startActivity(Intent(this, VoiceBrowserActivity::class.java)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DuplexClient.active?.onEvent = { event -> handler.post { model.render(event) } }
        // The server states its tuning once, in `hello`, to whatever screen was
        // attached at connect -- always the conversation. Adopt the cached
        // copy, or every control would show a default rather than what the
        // bridge is actually doing.
        DuplexClient.lastConfig?.let { model.applyConfig(it) }
        // A voice may have been installed on the browse screen.
        model.loadVoices()
        model.showReadbackVoices(Speaker.profiles)
        Speaker.onProfilesChanged = { profiles -> handler.post { model.showReadbackVoices(profiles) } }
    }

    override fun onPause() {
        super.onPause()
        DuplexClient.active?.onEvent = {}
        // Saved on the way out rather than per keystroke, so a half-typed
        // address is never what the next Connect uses.
        saveServerUrl(this, model.serverUrl)
        Speaker.onProfilesChanged = {}
    }
}
