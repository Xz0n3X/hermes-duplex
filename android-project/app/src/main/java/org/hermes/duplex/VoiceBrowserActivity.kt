package org.hermes.duplex

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.hermes.duplex.ui.Note
import org.hermes.duplex.ui.ScreenScaffold
import org.hermes.duplex.ui.theme.HermesTheme
import org.hermes.duplex.ui.theme.Palette
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The voices you don't have yet.
 *
 * The settings dropdown lists only what can be spoken immediately. Everything
 * else lives here, because tapping one means waiting for a 60-115 MB download,
 * and that is a different kind of decision from picking a voice mid-chat.
 */
class VoiceBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HermesTheme { VoiceBrowserScreen { finish() } } }
    }
}

private val http = OkHttpClient.Builder()
    // A voice download is tens of megabytes over the tailnet.
    .callTimeout(5, TimeUnit.MINUTES)
    .build()

private fun base(): String = DuplexClient.lastUrl
    .replaceFirst("ws://", "http://").replaceFirst("wss://", "https://").removeSuffix("/ws")

@Composable
private fun VoiceBrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ui = remember { Handler(Looper.getMainLooper()) }
    var status by remember { mutableStateOf(context.getString(R.string.loading)) }
    var query by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf(listOf<Pair<String, String>>()) }   // name to label

    fun load() {
        status = context.getString(R.string.loading)
        thread {
            val body = runCatching {
                http.newCall(Request.Builder().url("${base()}/voices?all=1").build())
                    .execute().use { it.body?.string().orEmpty() }
            }.getOrElse {
                ui.post { status = context.getString(R.string.load_failed, it.message) }
                return@thread
            }
            val found = mutableListOf<Pair<String, String>>()
            runCatching {
                val list = JSONObject(body).getJSONArray("voices")
                for (i in 0 until list.length()) {
                    val voice = list.getJSONObject(i)
                    if (voice.optString("engine") != "piper") continue
                    if (voice.optBoolean("downloaded")) continue
                    found += voice.getString("name") to voice.getString("label")
                }
            }
            ui.post {
                voices = found
                status = context.getString(R.string.available_count, found.size)
            }
        }
    }

    fun download(name: String, label: String) {
        status = context.getString(R.string.downloading, label)
        thread {
            val request = Request.Builder()
                .url("${base()}/voices/download?name=$name")
                .post(ByteArray(0).toRequestBody())
                .build()
            val body = runCatching {
                http.newCall(request).execute().use { it.body?.string().orEmpty() }
            }.getOrElse {
                ui.post { status = context.getString(R.string.download_failed, it.message) }
                return@thread
            }
            val ok = runCatching { JSONObject(body).optBoolean("ok") }.getOrDefault(false)
            ui.post {
                if (ok) {
                    Toast.makeText(context, context.getString(R.string.installed, label), Toast.LENGTH_SHORT).show()
                    // It is now usable, so it belongs on the settings list.
                    load()
                } else {
                    status = context.getString(R.string.download_failed, body.take(80))
                }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    ScreenScaffold(stringResource(R.string.more_voices), onBack, scroll = false) {
        Note(stringResource(R.string.tap_to_download))
        TextField(
            query, { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.filter_hint)) },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.bgRaised, unfocusedContainerColor = Palette.bgRaised,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Palette.textPrimary, unfocusedTextColor = Palette.textPrimary,
            ),
        )
        Text(status, color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        val shown = if (query.isBlank()) voices else voices.filter { it.second.contains(query, ignoreCase = true) }
        LazyColumn {
            items(shown, key = { it.first }) { (name, label) ->
                Column(Modifier.clickable { download(name, label) }) {
                    Text(label, color = Palette.textPrimary, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
                    HorizontalDivider(color = Palette.borderDefault)
                }
            }
        }
    }
}
