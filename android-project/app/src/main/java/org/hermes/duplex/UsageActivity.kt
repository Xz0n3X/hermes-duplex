package org.hermes.duplex

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hermes.duplex.ui.MainSession
import org.hermes.duplex.ui.Panel
import org.hermes.duplex.ui.ScreenScaffold
import org.hermes.duplex.ui.SoftButton
import org.hermes.duplex.ui.theme.HermesTheme
import org.hermes.duplex.ui.theme.Palette
import org.json.JSONObject

/**
 * Every account's limits at once: one card per engine the bridge reports,
 * refreshed every five minutes while the screen is open. The last answer is
 * kept, so an offline bridge shows stale numbers rather than a blank.
 */
class UsageActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("usage-dashboard", Context.MODE_PRIVATE) }
    private var event by mutableStateOf<JSONObject?>(null)
    private val refresh = object : Runnable {
        override fun run() { request(); handler.postDelayed(this, REFRESH_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesTheme { UsageScreen(event, onBack = { finish() }, onRefresh = { request() }) }
        }
    }

    override fun onResume() {
        super.onResume()
        DuplexClient.active?.onEvent = { e -> handler.post { accept(e) } }
        prefs.getString("last", null)?.let { saved ->
            runCatching { event = JSONObject(saved).put("cached", true) }
        }
        request()
        handler.postDelayed(refresh, REFRESH_MS)
    }

    override fun onPause() {
        DuplexClient.active?.onEvent = {}
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun request() {
        DuplexClient.active?.sendCommand(JSONObject().put("type", "usage_all"))
    }

    private fun accept(e: JSONObject) {
        if (e.optString("type") != "usage_all") return
        if (!e.optBoolean("cached")) prefs.edit().putString("last", e.toString()).apply()
        event = e
    }

    private companion object { const val REFRESH_MS = 5 * 60_000L }
}

private val USED = Color(0xFFF59E0B)
private val LEFT = Color(0xFF22C55E)
private val DANGER = Color(0xFFDC2626)

@Composable
private fun UsageScreen(event: JSONObject?, onBack: () -> Unit, onRefresh: () -> Unit) {
    ScreenScaffold("Account usage", onBack, actions = {
        SoftButton("Refresh", onRefresh, modifier = Modifier.padding(end = 8.dp))
    }) {
        if (event == null) {
            Text("Updating account limits…", color = Palette.textTertiary, fontSize = 13.sp)
            CircularProgressIndicator(Modifier.padding(top = 12.dp).size(28.dp), color = Palette.accent)
            return@ScreenScaffold
        }
        val updated = event.optLong("updated_at", 0L)
        val whenText = if (updated > 0) MainSession.formatTime(updated) else "unknown"
        Text(
            "Last updated $whenText" +
                if (event.optBoolean("cached")) " · updating…" else " · updates every 5 minutes",
            color = Palette.textTertiary, fontSize = 13.sp,
        )
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Legend("USED", USED)
            Legend("LEFT", LEFT)
        }
        val engines = event.optJSONObject("engines") ?: JSONObject()
        // Whatever the bridge has, rather than a fixed list. Remote machines
        // are added and removed from the phone, so a fixed list would show a
        // card for a server that was deleted and none for the one just added.
        val accounts = event.optJSONObject("accounts") ?: JSONObject()
        val ids = engines.keys().asSequence().toMutableList()
        // Stable and readable: this machine's engines first, then each remote.
        ids.sortBy { id ->
            (if (accounts.optJSONObject(id)?.optBoolean("remote") == true) 1 else 0).toString() + id
        }
        if (ids.isEmpty()) {
            Text("No engines reported", color = Palette.textSecondary, fontSize = 15.sp)
            return@ScreenScaffold
        }
        ids.forEach { id ->
            AccountCard(cardTitle(id, accounts.optJSONObject(id)), tintFor(id), engines.optJSONObject(id))
        }
    }
}

/** What to call one account's card: whatever the bridge named it, else the id. */
private fun cardTitle(id: String, about: JSONObject?): String {
    val label = about?.optString("label").orEmpty()
    if (label.isNotBlank()) return label
    return when (id) {
        "claude" -> "Claude · This Mac"
        "codex" -> "Codex"
        "claude_remote" -> "Claude · SSH server"
        else -> id
    }
}

/**
 * A colour per engine, derived rather than assigned. The two local engines
 * keep the colours they have always had; anything else gets a hue from its
 * own name, stable across restarts and distinct between machines.
 */
private fun tintFor(id: String): Color = when {
    id == "claude" -> Color(0xFFD97757)
    id == "codex" -> Color(0xFF2563EB)
    id.startsWith("codex") -> shade(id, 210f)
    else -> shade(id, 280f)
}

private fun shade(id: String, base: Float): Color {
    val spread = ((id.hashCode() % 50) + 50) % 50 - 25
    return Color(android.graphics.Color.HSVToColor(floatArrayOf((base + spread + 360f) % 360f, 0.62f, 0.72f)))
}

@Composable
private fun Legend(label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(10.dp).background(tint, CircleShape))
        Text(label, color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun AccountCard(title: String, accent: Color, value: JSONObject?) {
    Panel(Modifier.padding(bottom = 8.dp)) {
        Text(title, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            value?.optString("account_email").orEmpty().ifBlank { "Account identity unavailable" },
            color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp),
        )
        if (value == null) { Text("Unavailable", color = Palette.textSecondary, fontSize = 15.sp); return@Panel }
        val windows = value.optJSONArray("windows")
        if (windows == null || windows.length() == 0) {
            Text(value.optString("note").ifBlank { "Usage unavailable" }, color = Palette.textSecondary, fontSize = 15.sp)
            return@Panel
        }
        for (i in 0 until windows.length()) {
            if (i > 0) HorizontalDivider(Modifier.padding(top = 4.dp), color = Palette.borderDefault)
            windows.optJSONObject(i)?.let { Quota(it) }
        }
        val credits = value.optInt("reset_credits", -1)
        if (credits >= 0) {
            Text("$credits RESET CREDITS", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun Quota(item: JSONObject) {
    if (item.optBoolean("unavailable")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.optString("label").uppercase(), color = Palette.textTertiary, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(item.optString("note").ifBlank { "Not reported" }, color = Palette.textSecondary, fontSize = 12.sp)
        }
        return
    }
    val used = item.optDouble("used", 0.0).coerceIn(0.0, 100.0).toInt()
    val left = 100 - used
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.optString("label").uppercase(), color = Palette.textTertiary, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("$used% used", color = USED, fontSize = 13.sp)
        Text("  $left% left", color = if (left <= 20) DANGER else LEFT, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    LinearProgressIndicator(
        progress = { used / 100f },
        color = USED,
        trackColor = Palette.borderDefault,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(CircleShape),
    )
    val reset = item.optLong("resets_at", 0L)
    val resetText = item.optString("reset_text").ifBlank { if (reset > 0) MainSession.formatTime(reset) else "" }
    if (resetText.isNotBlank()) Text("Resets $resetText", color = Palette.textSecondary, fontSize = 11.sp)
}
