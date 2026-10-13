package org.hermes.duplex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.hermes.duplex.ui.ActionButton
import org.hermes.duplex.ui.ScreenScaffold
import org.hermes.duplex.ui.SoftButton
import org.hermes.duplex.ui.theme.HermesTheme
import org.hermes.duplex.ui.theme.Palette
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One screen that answers "what is it actually doing?".
 *
 * Copy puts the phone-side event log, the device's audio capabilities and the
 * server's own log into the clipboard in one tap, so a bad session can be
 * pasted somewhere useful instead of described from memory.
 */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HermesTheme { DebugScreen(this) { finish() } } }
    }
}

private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

/** ws://host:8770/ws -> http://host:8770/debug */
private fun debugUrl(): String = DuplexClient.lastUrl
    .replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
    .removeSuffix("/ws") + "/debug"

@Composable
private fun DebugScreen(activity: ComponentActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    var output by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val ui = remember { Handler(Looper.getMainLooper()) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun refresh() {
        val local = buildString {
            appendLine("=== device ===")
            append(EventLog.deviceReport(context))
            appendLine()
            appendLine("=== connection ===")
            appendLine("url        : ${DuplexClient.lastUrl}")
            appendLine("connected  : ${DuplexClient.active?.connected ?: false}")
            appendLine()
            appendLine("=== app events (newest last) ===")
            EventLog.snapshot().forEach { appendLine(it) }
        }
        output = local
        thread {
            val server = runCatching {
                http.newCall(Request.Builder().url(debugUrl()).build()).execute().use { it.body?.string().orEmpty() }
            }.getOrElse { "server /debug unreachable: ${it.message}" }
            // Server JSON is one long line; unreadable on a phone without this.
            val pretty = runCatching { JSONObject(server).toString(2) }.getOrDefault(server)
            ui.post {
                output = buildString {
                    append(local); appendLine()
                    appendLine("=== server ${debugUrl()} ===")
                    appendLine(pretty)
                }
            }
        }
    }

    fun command(type: String, confirmation: String) {
        val client = DuplexClient.active
        if (client == null) { toast("not connected"); return }
        client.sendCommand(JSONObject().put("type", type))
        toast(confirmation)
        ui.postDelayed({ refresh() }, 400)
    }

    LaunchedEffect(Unit) { refresh() }

    ScreenScaffold(stringResource(R.string.debug), onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            SoftButton(stringResource(R.string.refresh), { refresh() })
            SoftButton(stringResource(R.string.copy_all), {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("hermes-duplex debug", output))
                toast("copied ${output.length} chars")
            })
            SoftButton(stringResource(R.string.clear), { EventLog.clear(); refresh() })
        }
        // Commands. /new and /stop mirror the Hermes gateway commands of the
        // same name; the gateway is not in this path, so the bridge implements
        // them itself.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        ) {
            SoftButton(stringResource(R.string.cmd_reset), {
                command("reset", "conversation reset")
                // Same command as the conversation screen's New, so it must
                // do the same thing to the transcript.
                ConversationStore.clear()
            })
            SoftButton(stringResource(R.string.cmd_stop), { command("stop", "stop sent") })
            SoftButton(stringResource(R.string.cmd_recal), { command("recalibrate", "recalibrating") })
            SoftButton(stringResource(R.string.cmd_stats), { command("stats", "stats requested") })
        }
        // A typed prompt exercises Hermes end to end without the microphone,
        // which separates "voice is broken" from "the model is broken".
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                prompt, { prompt = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.prompt_hint)) },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Palette.bgRaised, unfocusedContainerColor = Palette.bgRaised,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Palette.textPrimary, unfocusedTextColor = Palette.textPrimary,
                ),
            )
            ActionButton(stringResource(R.string.send), {
                val text = prompt.trim()
                if (text.isEmpty()) return@ActionButton
                val client = DuplexClient.active
                if (client == null) { toast("not connected"); return@ActionButton }
                client.sendCommand(JSONObject().put("type", "prompt").put("text", text))
                prompt = ""
                toast("sent")
            })
        }
        SelectionContainer {
            Text(
                output,
                color = Palette.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.bgRaised)
                    .padding(10.dp),
            )
        }
    }
}
