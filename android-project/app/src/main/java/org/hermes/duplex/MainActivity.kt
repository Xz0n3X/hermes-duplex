package org.hermes.duplex

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.hermes.duplex.ui.Destination
import org.hermes.duplex.ui.MainScreen
import org.hermes.duplex.ui.MainSession
import org.hermes.duplex.ui.theme.HermesTheme

/**
 * The chat screen, and the voice stage that folds down over it.
 *
 * The activity owns no state worth keeping. The socket belongs to
 * [DuplexService], the thread to [ConversationStore], the offers to
 * [DuplexClient] and the timings to [TimingStore], so closing the screen or
 * letting it sleep costs nothing. Everything else lives in [MainSession].
 */
class MainActivity : ComponentActivity() {

    private lateinit var session: MainSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        session = MainSession(this)
        setContent {
            HermesTheme {
                MainScreen(session) { destination ->
                    val target = when (destination) {
                        Destination.SETTINGS -> SettingsActivity::class.java
                        Destination.TIMING -> TimingActivity::class.java
                        Destination.DEBUG -> DebugActivity::class.java
                        Destination.USAGE -> UsageActivity::class.java
                        Destination.REMOTES -> RemotesActivity::class.java
                    }
                    startActivity(Intent(this, target))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        session.onResume()
    }

    override fun onPause() {
        super.onPause()
        session.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session.onDestroy(isFinishing)
    }

    // Deliberately no stop() in onDestroy: closing the screen must not end the
    // conversation. The notification is how you get back, or end it.
}
