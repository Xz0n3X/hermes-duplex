package org.hermes.duplex

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hermes.duplex.ui.ActionButton
import org.hermes.duplex.ui.Dropdown
import org.hermes.duplex.ui.Note
import org.hermes.duplex.ui.Panel
import org.hermes.duplex.ui.ScreenScaffold
import org.hermes.duplex.ui.SectionLabel
import org.hermes.duplex.ui.SoftButton
import org.hermes.duplex.ui.theme.HermesTheme
import org.hermes.duplex.ui.theme.Palette
import org.json.JSONArray
import org.json.JSONObject

/**
 * The machines the bridge can reach, edited from the phone.
 *
 * A remote Claude or Codex session used to be one hardcoded address in the
 * bridge's source. So the list is data, the bridge keeps it, and this edits
 * it. Each machine turns into one engine per CLI it runs, which is why the
 * card names the engines it produces: the connection you set up here is the
 * switch you pick in Settings.
 *
 * About the password: it goes up once, when you type it, and never comes
 * back. The bridge sends `has_password` instead, so this screen can say a
 * password is set and offer to replace it, and an empty password field means
 * "leave it alone" rather than "clear it" -- otherwise editing a port would
 * silently delete the credential.
 */
class RemotesActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var items by mutableStateOf(listOf<JSONObject>())
    private var sandboxes by mutableStateOf(listOf("read-only", "workspace-write", "danger-full-access"))
    private var agentIds by mutableStateOf(listOf("claude", "codex"))
    private var editable by mutableStateOf(true)
    private var alert by mutableStateOf<Pair<String, String>?>(null)
    /** Which card the last test was launched from, so its answer lands there. */
    private var testing = ""
    private val testResults = mutableStateMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesTheme {
                RemotesScreen(
                    items, sandboxes, agentIds, editable, testResults, alert,
                    onDismissAlert = { alert = null },
                    onBack = { finish() },
                    onSave = { send("save_remote", it) },
                    onTest = { remote, id -> testing = id; testResults[id] = getString(R.string.remote_testing); send("test_remote", remote) },
                    onDelete = { id -> DuplexClient.active?.sendCommand(JSONObject().put("type", "delete_remote").put("id", id)) },
                )
            }
        }
    }

    private fun send(type: String, remote: JSONObject) {
        DuplexClient.active?.sendCommand(JSONObject().put("type", type).put("remote", remote))
    }

    override fun onResume() {
        super.onResume()
        DuplexClient.active?.onEvent = { event -> handler.post { onServerEvent(event) } }
        DuplexClient.active?.sendCommand(JSONObject().put("type", "remotes"))
    }

    override fun onPause() {
        DuplexClient.active?.onEvent = {}
        super.onPause()
    }

    private fun onServerEvent(event: JSONObject) {
        when (event.optString("type")) {
            "remotes" -> {
                val list = event.optJSONArray("items") ?: JSONArray()
                items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                editable = event.optBoolean("editable", true)
                event.optJSONArray("sandboxes")?.let { sandboxes = it.strings() }
                event.optJSONArray("agents")?.let { agentIds = it.strings() }
                event.optString("note").takeIf { it.isNotBlank() }
                    ?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            }
            // The bridge refused what was typed, and said why in a sentence
            // meant to be read. Nothing was saved.
            "remote_error" -> alert = getString(R.string.remote_not_saved) to event.optString("text")
            "remote_test" -> {
                val ok = event.optBoolean("ok")
                val text = event.optString("text")
                testResults[testing] = (if (ok) "OK: " else "Failed: ") + text
                alert = getString(if (ok) R.string.remote_reachable else R.string.remote_unreachable) to text
            }
        }
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList()
    else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

@Composable
private fun RemotesScreen(
    items: List<JSONObject>,
    sandboxes: List<String>,
    agentIds: List<String>,
    editable: Boolean,
    testResults: Map<String, String>,
    alert: Pair<String, String>?,
    onDismissAlert: () -> Unit,
    onBack: () -> Unit,
    onSave: (JSONObject) -> Unit,
    onTest: (JSONObject, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }

    ScreenScaffold(stringResource(R.string.remotes), onBack) {
        Note(stringResource(R.string.remotes_note))
        if (!editable) {
            Note(stringResource(R.string.remotes_readonly))
            return@ScreenScaffold
        }
        items.forEach { item ->
            val id = item.optString("id")
            val port = item.optInt("port", 22)
            Panel(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.optString("label").ifBlank { item.optString("host") },
                        color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    // Only the flag: a machine switched off keeps its settings
                    // and its password, it just stops being offered.
                    Switch(
                        checked = item.optBoolean("enabled", true),
                        onCheckedChange = { onSave(JSONObject().put("id", id).put("enabled", it)) },
                    )
                }
                Line(item.optString("user") + "@" + item.optString("host") + if (port != 22) ":$port" else "", Palette.textSecondary)
                val engines = item.optJSONObject("engines")
                val offered = engines?.keys()?.asSequence()?.joinToString("   ") { "$it: " + engines.optString(it) }.orEmpty()
                if (offered.isNotEmpty()) Line(offered, Palette.textTertiary)
                Line(
                    when {
                        item.optString("identity").isNotBlank() -> stringResource(R.string.remote_uses_key, item.optString("identity"))
                        item.optBoolean("has_password") -> stringResource(R.string.remote_has_password)
                        else -> stringResource(R.string.remote_no_credential)
                    },
                    Palette.textTertiary,
                )
                if (item.optJSONArray("agents").strings().contains("codex")) {
                    Line(stringResource(R.string.remote_sandbox_is, item.optString("sandbox")), Palette.textTertiary)
                }
                testResults[id]?.let { Line(it, Palette.textSecondary) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(stringResource(R.string.remote_edit), { editing = item })
                    SoftButton(stringResource(R.string.remote_test), { onTest(item, id) })
                    SoftButton(stringResource(R.string.remote_delete), { deleting = item }, color = Palette.statusFailed)
                }
            }
        }
        if (items.isEmpty()) Note(stringResource(R.string.remotes_empty))
        ActionButton(stringResource(R.string.remote_add), { adding = true }, modifier = Modifier.padding(top = 8.dp))
    }

    if (adding || editing != null) {
        RemoteForm(
            item = editing, sandboxes = sandboxes, agentIds = agentIds,
            onDismiss = { adding = false; editing = null },
            onSave = onSave,
            // Test without saving, which is the order anyone setting up a new
            // machine actually wants: find out whether it works, then keep it.
            onTest = { remote -> onTest(remote, editing?.optString("id").orEmpty()) },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.remote_delete_title, item.optString("label").ifBlank { item.optString("host") })) },
            text = { Text(stringResource(R.string.remote_delete_body)) },
            confirmButton = {
                TextButton(onClick = { onDelete(item.optString("id")); deleting = null }) {
                    Text(stringResource(R.string.remote_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    alert?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = onDismissAlert,
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = onDismissAlert) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, fontSize = 13.sp)
}

@Composable
private fun RemoteForm(
    item: JSONObject?,
    sandboxes: List<String>,
    agentIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (JSONObject) -> Unit,
    onTest: (JSONObject) -> Unit,
) {
    var label by remember { mutableStateOf(item?.optString("label").orEmpty()) }
    var host by remember { mutableStateOf(item?.optString("host").orEmpty()) }
    var user by remember { mutableStateOf(item?.optString("user") ?: "root") }
    var port by remember { mutableStateOf((item?.optInt("port", 22) ?: 22).toString()) }
    var password by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf(item?.optString("identity").orEmpty()) }
    val chosen = remember { mutableStateOf((item?.optJSONArray("agents")?.strings() ?: agentIds).toSet()) }
    var sandbox by remember { mutableStateOf(sandboxes.indexOf(item?.optString("sandbox") ?: sandboxes.first()).coerceAtLeast(0)) }
    val commands = item?.optJSONObject("commands")
    var claudePath by remember { mutableStateOf(commands?.optString("claude").orEmpty()) }
    var codexPath by remember { mutableStateOf(commands?.optString("codex").orEmpty()) }

    fun collect(): JSONObject {
        val payload = JSONObject()
            .put("label", label.trim())
            .put("host", host.trim())
            .put("user", user.trim())
            .put("port", port.trim().ifBlank { "22" })
            .put("identity", identity.trim())
            .put("sandbox", sandboxes[sandbox])
            .put("agents", JSONArray(agentIds.filter { it in chosen.value }))
            .put("commands", JSONObject().put("claude", claudePath.trim()).put("codex", codexPath.trim()))
            .put("enabled", item?.optBoolean("enabled", true) ?: true)
        item?.optString("id")?.takeIf { it.isNotBlank() }?.let { payload.put("id", it) }
        password.takeIf { it.isNotEmpty() }?.let { payload.put("password", it) }
        return payload
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (item == null) R.string.remote_add else R.string.remote_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(R.string.remote_field_label, label) { label = it }
                Field(R.string.remote_field_host, host) { host = it }
                Field(R.string.remote_field_user, user) { user = it }
                Field(R.string.remote_field_port, port, number = true) { port = it }
                Field(R.string.remote_field_password, password, secret = true) { password = it }
                Note(stringResource(if (item?.optBoolean("has_password") == true) R.string.remote_password_kept else R.string.remote_password_new))
                Field(R.string.remote_field_identity, identity) { identity = it }

                SectionLabel(stringResource(R.string.remote_field_agents))
                agentIds.forEach { agent ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = agent in chosen.value,
                            onCheckedChange = { on -> chosen.value = if (on) chosen.value + agent else chosen.value - agent },
                        )
                        Text(agent, fontSize = 14.sp)
                    }
                }
                SectionLabel(stringResource(R.string.remote_field_sandbox))
                Dropdown(sandboxes, sandbox, { sandbox = it })
                Note(stringResource(R.string.remote_sandbox_note))

                SectionLabel(stringResource(R.string.remote_field_paths))
                Field(R.string.remote_field_claude_path, claudePath) { claudePath = it }
                Field(R.string.remote_field_codex_path, codexPath) { codexPath = it }
                Note(stringResource(R.string.remote_paths_note))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(collect()); onDismiss() }) { Text(stringResource(R.string.remote_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                TextButton(onClick = { onTest(collect()) }) { Text(stringResource(R.string.remote_test)) }
            }
        },
    )
}

@Composable
private fun Field(label: Int, value: String, number: Boolean = false, secret: Boolean = false, onChange: (String) -> Unit) {
    SectionLabel(stringResource(label))
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when { secret -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text },
            autoCorrectEnabled = false,
        ),
    )
}
