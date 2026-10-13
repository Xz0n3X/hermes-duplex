package org.hermes.duplex.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.hermes.duplex.DuplexClient
import org.hermes.duplex.EventLog
import org.hermes.duplex.R
import org.hermes.duplex.SettingsActivity
import org.hermes.duplex.Speaker
import org.hermes.duplex.ui.theme.Palette
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class EngineChoice(val id: String, val label: String, val note: String, val installed: Boolean)

/** The settings screen's state: the server's tuning, mirrored. */
class SettingsModel(private val activity: ComponentActivity) {
    private val prefs = activity.getSharedPreferences("hermes-duplex", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    var serverUrl by mutableStateOf(SettingsActivity.serverUrl(activity))

    var floor by mutableFloatStateOf(200f)
    var barge by mutableFloatStateOf(35f)      // multiplier x 10
    var silence by mutableFloatStateOf(800f)
    var bargeEnabled by mutableStateOf(true)
    var memory by mutableStateOf(false)
    var history by mutableStateOf(true)
    var web by mutableStateOf(true)

    var engine by mutableStateOf("hermes")
    var engines by mutableStateOf(listOf<EngineChoice>())
    var anyMissing by mutableStateOf(false)

    var level by mutableFloatStateOf(0f)
    var threshold by mutableFloatStateOf(0f)
    var levelText by mutableStateOf(activity.getString(R.string.level_idle))

    var voiceNames by mutableStateOf(listOf<String>())
    var voiceLabels by mutableStateOf(listOf<String>())
    var voiceIndex by mutableIntStateOf(0)

    var profiles by mutableStateOf(Speaker.profiles)
    var readbackIndex by mutableIntStateOf(0)
    var llmIndex by mutableIntStateOf(0)
    var systemIndex by mutableIntStateOf(0)

    private val client: DuplexClient? get() = DuplexClient.active

    init {
        Speaker.start(activity)
        showReadbackVoices(Speaker.profiles)
    }

    fun tune(vararg pairs: Pair<String, Any>) {
        client?.setTuning(*pairs)
    }

    fun render(event: JSONObject) {
        when (event.optString("type")) {
            "level" -> {
                val rms = event.optDouble("rms")
                val limit = event.optDouble("threshold")
                level = rms.toFloat()
                threshold = limit.toFloat()
                levelText = activity.getString(
                    R.string.level_value, rms, limit,
                    if (event.optBoolean("speech")) "SPEECH" else "quiet",
                )
            }
            "hello", "config" -> applyConfig(event)
        }
    }

    /** Mirror the server's tuning into the controls. */
    fun applyConfig(event: JSONObject) {
        floor = event.optDouble("absolute_floor", 200.0).toFloat()
        barge = (event.optDouble("barge_in_multiplier", 3.5) * 10).toFloat()
        silence = event.optInt("silence_end_ms", 800).toFloat()
        bargeEnabled = event.optBoolean("barge_in_enabled", true)
        memory = event.optBoolean("memory_recall", false)
        history = event.optBoolean("history", true)
        web = event.optBoolean("web_search", true)
        renderEngines(event)
        event.optString("voice").takeIf { it.isNotEmpty() }?.let { voice ->
            prefs.edit().putString("voice", voice).apply()
            voiceNames.indexOf(voice).takeIf { it >= 0 }?.let { voiceIndex = it }
        }
    }

    /**
     * Build the engine list from what the bridge reports. Hermes is prepended
     * here rather than sent, since it is the bridge itself and is always
     * available -- if it were not, there would be no config event to read.
     */
    private fun renderEngines(event: JSONObject) {
        engine = event.optString("engine", "hermes")
        val offered = mutableListOf(
            EngineChoice("hermes", activity.getString(R.string.engine_hermes),
                activity.getString(R.string.engine_hermes_note), true)
        )
        var missing = false
        event.optJSONArray("agents")?.let { list ->
            for (index in 0 until list.length()) {
                val agent = list.optJSONObject(index) ?: continue
                val available = agent.optBoolean("available")
                if (!available) missing = true
                offered += EngineChoice(
                    agent.optString("id"),
                    agent.optString("label") + if (available) "" else " — not installed",
                    agent.optString("note"),
                    available,
                )
            }
        }
        engines = offered
        anyMissing = missing
    }

    fun showReadbackVoices(list: List<Speaker.Profile>) {
        profiles = list
        if (list.isEmpty()) return
        val selected = Speaker.selectedProfileName
        readbackIndex = if (selected.isNullOrBlank()) 0 else list.indexOfFirst { it.name == selected }.coerceAtLeast(0)
        llmIndex = routeIndex(Speaker.SpeechContext.LLM_RESPONSE)
        systemIndex = routeIndex(Speaker.SpeechContext.SYSTEM_RESPONSE)
    }

    private fun routeIndex(context: Speaker.SpeechContext): Int {
        val selected = Speaker.selectedProfileName(context) ?: return 0
        return profiles.indexOfFirst { it.name == selected }.let { if (it < 0) 0 else it + 1 }
    }

    fun chooseVoice(index: Int) {
        voiceIndex = index
        voiceNames.getOrNull(index)?.let {
            prefs.edit().putString("voice", it).apply()
            tune("voice" to it)
        }
    }

    fun previewVoice() {
        voiceNames.getOrNull(voiceIndex)?.let { voice ->
            client?.sendCommand(JSONObject().put("type", "preview_voice").put("voice", voice))
        }
    }

    fun recalibrate() {
        client?.sendCommand(JSONObject().put("type", "recalibrate"))
    }

    fun chooseReadback(index: Int) {
        readbackIndex = index
        profiles.getOrNull(index)?.let { Speaker.selectProfile(it.name) }
    }

    fun chooseRoute(context: Speaker.SpeechContext, index: Int) {
        if (context == Speaker.SpeechContext.LLM_RESPONSE) llmIndex = index else systemIndex = index
        // Index 0 is "use the default phone voice".
        Speaker.selectProfile(context, profiles.getOrNull(index - 1)?.name)
    }

    fun previewReadback() {
        Speaker.say("This is the selected phone read-back voice.", queue = false, key = "readback-preview")
    }

    /** GET /voices and fill the dropdown. Falls back silently if unreachable. */
    fun loadVoices() {
        val url = DuplexClient.lastUrl
            .replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
            .removeSuffix("/ws") + "/voices"
        thread {
            val body = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
            }.getOrElse {
                EventLog.add("voice list failed: ${it.message}")
                return@thread
            }
            val labels = mutableListOf<String>()
            val names = mutableListOf<String>()
            runCatching {
                val voices = JSONObject(body).getJSONArray("voices")
                for (index in 0 until voices.length()) {
                    val voice = voices.getJSONObject(index)
                    names += voice.getString("name")
                    labels += voice.getString("label")
                }
            }.onFailure {
                EventLog.add("voice list unparseable: ${it.message}")
                return@thread
            }
            ui.post {
                voiceNames = names
                voiceLabels = labels
                // The choice has to be put back or every return to this screen
                // silently changes voice.
                prefs.getString("voice", null)?.let { want ->
                    names.indexOf(want).takeIf { it >= 0 }?.let { voiceIndex = it }
                }
                EventLog.add("loaded ${names.size} voices")
            }
        }
    }
}

@Composable
fun SettingsScreen(
    model: SettingsModel,
    onBack: () -> Unit,
    onRemotes: () -> Unit,
    onMoreVoices: () -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.settings), onBack = onBack) {
        // -- the bridge
        SectionLabel(stringResource(R.string.section_connection))
        TextField(
            value = model.serverUrl,
            onValueChange = { model.serverUrl = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.server_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, Palette.borderDefault), RoundedCornerShape(12.dp)),
            colors = fieldColors(),
        )
        Note(stringResource(R.string.server_note))

        // -- the voice that answers
        SectionLabel(stringResource(R.string.voice_label))
        Dropdown(model.voiceLabels, model.voiceIndex, { model.chooseVoice(it) }, placeholder = "Loading voices…")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton(stringResource(R.string.preview), { model.previewVoice() })
            SoftButton(stringResource(R.string.more), onMoreVoices)
        }

        SectionLabel(stringResource(R.string.readback_voice_label))
        val names = model.profiles.map { it.label }
        Dropdown(names, model.readbackIndex, { model.chooseReadback(it) }, placeholder = "No phone voices yet")
        val routes = listOf("Use default phone voice") + names
        SectionLabel("LLM response voice")
        Dropdown(routes, model.llmIndex, { model.chooseRoute(org.hermes.duplex.Speaker.SpeechContext.LLM_RESPONSE, it) })
        SectionLabel("System / AI response voice")
        Dropdown(routes, model.systemIndex, { model.chooseRoute(org.hermes.duplex.Speaker.SpeechContext.SYSTEM_RESPONSE, it) })
        SoftButton(
            stringResource(R.string.readback_preview), { model.previewReadback() },
            modifier = Modifier.padding(top = 8.dp),
        )

        // -- what a turn may do
        SectionLabel(stringResource(R.string.switches_label))
        SwitchRow(stringResource(R.string.sw_memory), model.memory) {
            model.memory = it; model.tune("memory_recall" to it)
        }
        SwitchRow(stringResource(R.string.sw_history), model.history) {
            model.history = it; model.tune("history" to it)
        }
        SwitchRow(stringResource(R.string.sw_web), model.web) {
            model.web = it; model.tune("web_search" to it)
        }

        // -- who answers
        SectionLabel(stringResource(R.string.sw_engine))
        model.engines.forEach { choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = choice.installed) {
                        model.engine = choice.id
                        model.tune("engine" to choice.id)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = model.engine == choice.id,
                    onClick = null,
                    enabled = choice.installed,
                    colors = RadioButtonDefaults.colors(selectedColor = Palette.accent),
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        choice.label,
                        color = if (choice.installed) Palette.textPrimary else Palette.textTertiary,
                        fontSize = 15.sp,
                    )
                    if (choice.note.isNotEmpty()) Note(choice.note)
                }
            }
        }
        Note(stringResource(if (model.anyMissing) R.string.sw_engine_missing else R.string.sw_engine_note))
        SoftButton(
            stringResource(R.string.remotes), onRemotes,
            modifier = Modifier.padding(top = 8.dp),
        )

        // -- the microphone
        SectionLabel(stringResource(R.string.section_mic))
        Text(model.levelText, color = Palette.textSecondary, fontSize = 12.sp)
        Meter(model.level / 3000f, Palette.stateSent)
        Meter(model.threshold / 3000f, Palette.statusWarning)

        CommitSlider(
            label = stringResource(R.string.floor_label),
            value = model.floor, range = 0f..3000f,
            onLabel = { stringResource(R.string.floor_value, it.toInt()) },
            onCommit = { model.floor = it; model.tune("absolute_floor" to it.toDouble()) },
        )
        CommitSlider(
            label = stringResource(R.string.barge_label),
            value = model.barge, range = 10f..100f,
            onLabel = { stringResource(R.string.barge_value, it.coerceAtLeast(10f) / 10.0) },
            onCommit = { model.barge = it; model.tune("barge_in_multiplier" to (it.coerceAtLeast(10f) / 10.0)) },
        )
        CommitSlider(
            label = stringResource(R.string.silence_label),
            value = model.silence, range = 200f..3000f,
            onLabel = { stringResource(R.string.silence_value, it.toInt()) },
            onCommit = { model.silence = it; model.tune("silence_end_ms" to it.toInt().coerceAtLeast(200)) },
        )
        SwitchRow(stringResource(R.string.barge_enabled), model.bargeEnabled) {
            model.bargeEnabled = it; model.tune("barge_in_enabled" to it)
        }
        SoftButton(
            stringResource(R.string.recalibrate), { model.recalibrate() },
            modifier = Modifier.padding(top = 4.dp),
        )
        Note(stringResource(R.string.tuning_note))
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Meter(fraction: Float, color: Color) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        color = color,
        trackColor = Palette.borderDefault,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Palette.bgRaised,
    unfocusedContainerColor = Palette.bgRaised,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    cursorColor = Palette.accent,
)


