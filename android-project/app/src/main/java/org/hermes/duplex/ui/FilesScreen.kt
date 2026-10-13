package org.hermes.duplex.ui

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hermes.duplex.files.Change
import org.hermes.duplex.files.FileSource
import org.hermes.duplex.files.Kind
import org.hermes.duplex.files.Linked
import org.hermes.duplex.files.Node
import org.hermes.duplex.files.copyNode
import org.hermes.duplex.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesScreen(model: FilesModel, onBack: () -> Unit) {
    ScreenScaffold("Files", onBack) {
        // Which place you are in.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("This phone", "Drives", "GitHub").forEachIndexed { index, label ->
                val on = model.tab == index
                Text(
                    label,
                    color = if (on) Color.White else Palette.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) Palette.accent else Palette.bgRaised)
                        .clickable { model.tab = index }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Progress, then the outcome, as a sentence.
        if (model.message.isNotEmpty()) {
            Text(
                model.message,
                color = if (model.busy) Palette.statusRunning else Palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.bgRaised)
                    .padding(10.dp),
            )
        }

        when (model.tab) {
            0 -> Browser(model.workspace, model)
            1 -> DrivesTab(model)
            else -> GitHubTab(model)
        }
    }
    FilesDialogs(model)
}

// -- the browser ------------------------------------------------------------------------------

@Composable
private fun Browser(source: FileSource, model: FilesModel) {
    val context = LocalContext.current
    var stack by remember(source) { mutableStateOf(listOf(source.root)) }
    var items by remember(source) { mutableStateOf<List<Node>?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var editing by remember { mutableStateOf<Node?>(null) }
    var menuFor by remember { mutableStateOf<Node?>(null) }
    val dir = stack.last()

    LaunchedEffect(source, dir, reload, model.version) {
        error = null
        try {
            items = withContext(Dispatchers.IO) {
                source.list(dir).sortedWith(compareByDescending<Node> { it.isDir }.thenBy { it.name.lowercase() })
            }
        } catch (e: Exception) {
            items = emptyList()
            error = e.message ?: "Could not open this folder"
        }
    }

    fun attempt(start: String, work: () -> Unit) = model.task(start) { work(); "Done" }

    // Where you are, and the way back up.
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (stack.size > 1) {
            Text(
                "↑", color = Palette.accent, fontSize = 20.sp,
                modifier = Modifier.clickable { stack = stack.dropLast(1) }.padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        Text(
            stack.joinToString(" / ") { it.name },
            color = Palette.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SoftButton("New folder", { prompt = Prompt.NewFolder })
        SoftButton("New file", { prompt = Prompt.NewFile })
        SoftButton("Upload", { model.upload(source, dir) })
        model.clip?.let { clip ->
            SoftButton("Paste ${clip.node.name.take(18)}", {
                attempt("Copying…") { copyNode(clip.source, clip.node, source, dir) }
            }, color = Palette.accent)
        }
        SoftButton("Refresh", { reload++ })
    }
    error?.let { Text(it, color = Palette.statusFailed, fontSize = 13.sp) }

    val shown = items
    when {
        shown == null -> Text("Loading…", color = Palette.textTertiary, fontSize = 13.sp)
        shown.isEmpty() && error == null -> Note("This folder is empty.")
        else -> shown.take(300).forEach { node ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        if (node.isDir) stack = stack + node
                        else if (isEditable(node)) editing = node
                        else openOutside(context, source, node)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (node.isDir) "📁" else "📄", fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(node.name, color = Palette.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = listOfNotNull(
                        if (node.isDir) null else sizeText(node.size),
                        if (node.modified > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(node.modified)) else null,
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, color = Palette.textTertiary, fontSize = 11.sp)
                }
                Box2 {
                    Text(
                        "⋮", color = Palette.textSecondary, fontSize = 20.sp,
                        modifier = Modifier.clickable { menuFor = node }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    DropdownMenu(expanded = menuFor == node, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Copy") }, onClick = {
                            model.clip = Clip(source, node); menuFor = null
                            model.say("Copied ${node.name}. Open another folder or drive and tap Paste.")
                        })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; prompt = Prompt.Rename(node) })
                        if (!node.isDir) {
                            DropdownMenuItem(text = { Text("Open with…") }, onClick = { menuFor = null; openOutside(context, source, node) })
                            DropdownMenuItem(text = { Text("Edit as text") }, onClick = { menuFor = null; editing = node })
                        }
                        DropdownMenuItem(text = { Text("Delete", color = Palette.statusFailed) }, onClick = { menuFor = null; prompt = Prompt.Delete(node) })
                    }
                }
            }
            HorizontalDivider(color = Palette.borderDefault)
        }
    }
    if ((items?.size ?: 0) > 300) Note("Showing the first 300 of ${items!!.size}.")

    prompt?.let { current ->
        PromptDialog(
            current,
            onDismiss = { prompt = null },
            onConfirm = { text ->
                prompt = null
                when (current) {
                    Prompt.NewFolder -> attempt("Creating folder…") { source.mkdir(dir, text) }
                    Prompt.NewFile -> attempt("Creating file…") { source.openWrite(dir, text, "text/plain").close() }
                    is Prompt.Rename -> attempt("Renaming…") { source.rename(current.node, text) }
                    is Prompt.Delete -> attempt("Deleting…") { source.delete(current.node) }
                }
            },
        )
    }
    editing?.let { node ->
        TextEditor(source, dir, node, onClose = { editing = null; reload++ }, model = model)
    }
}

@Composable
private fun Box2(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box { content() }

private sealed interface Prompt {
    data object NewFolder : Prompt
    data object NewFile : Prompt
    data class Rename(val node: Node) : Prompt
    data class Delete(val node: Node) : Prompt
}

@Composable
private fun PromptDialog(prompt: Prompt, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(prompt) { mutableStateOf((prompt as? Prompt.Rename)?.node?.name.orEmpty()) }
    val delete = prompt as? Prompt.Delete
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(when (prompt) {
                Prompt.NewFolder -> "New folder"
                Prompt.NewFile -> "New file"
                is Prompt.Rename -> "Rename"
                is Prompt.Delete -> "Delete “${prompt.node.name}”?"
            })
        },
        text = {
            if (delete != null) {
                Text(if (delete.node.isDir) "The folder and everything in it will be deleted. This cannot be undone."
                else "This cannot be undone.")
            } else {
                TextField(text, { text = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = delete != null || text.isNotBlank()) {
                Text(if (delete != null) "Delete" else "OK", color = if (delete != null) Palette.statusFailed else Palette.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A text file, edited in place. Anything large or binary is not offered. */
@Composable
private fun TextEditor(source: FileSource, dir: Node, node: Node, onClose: () -> Unit, model: FilesModel) {
    var text by remember(node) { mutableStateOf<String?>(null) }
    var failure by remember(node) { mutableStateOf<String?>(null) }
    LaunchedEffect(node) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (node.size > MAX_EDIT) error("Too large to edit here (${sizeText(node.size)}, limit ${sizeText(MAX_EDIT)})")
                val bytes = source.openRead(node).use { it.readBytes() }
                if (bytes.any { it == 0.toByte() }) error("This is not a text file")
                String(bytes, Charsets.UTF_8)
            }
        }.fold({ text = it }, { failure = it.message })
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                failure != null -> Text(failure!!)
                text == null -> Text("Loading…")
                else -> TextField(
                    value = text!!, onValueChange = { text = it },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 460.dp),
                )
            }
        },
        confirmButton = {
            if (text != null) {
                TextButton(onClick = {
                    val body = text!!
                    model.task("Saving ${node.name}…") {
                        source.openWrite(dir, node.name, node.mime.ifBlank { "text/plain" }).use { it.write(body.toByteArray()) }
                        "Saved ${node.name}"
                    }
                    onClose()
                }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(if (text == null) "Close" else "Cancel") } },
    )
}

private const val MAX_EDIT = 1_000_000L

private val TEXT_EXT = setOf(
    "txt", "md", "json", "kt", "kts", "java", "xml", "py", "js", "ts", "tsx", "jsx", "html", "css",
    "yml", "yaml", "toml", "gradle", "properties", "sh", "csv", "log", "ini", "cfg", "conf", "sql", "c", "h", "cpp",
)

private fun isEditable(node: Node): Boolean =
    node.mime.startsWith("text/") || node.name.substringAfterLast('.', "").lowercase() in TEXT_EXT

private fun openOutside(context: android.content.Context, source: FileSource, node: Node) {
    val uri = runCatching { source.shareUri(context, node) }.getOrNull()
    if (uri == null) {
        android.widget.Toast.makeText(context, "This cannot be opened from here", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val mime = node.mime.ifBlank {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(node.name.substringAfterLast('.', "").lowercase()) ?: "*/*"
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                node.name,
            )
        )
    }.onFailure {
        android.widget.Toast.makeText(context, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1048576.0)
}

// -- connected drives ------------------------------------------------------------------------------

@Composable
private fun DrivesTab(model: FilesModel) {
    Note(
        "Connect Google Drive, a USB drive, an SD card or any folder. Android shows its own picker, " +
            "so Hermes Duplex never sees your Google password or any token, only the folder you choose. " +
            "For Google Drive, pick it in the picker's side menu (the Drive app must be installed)."
    )
    ActionButton("Connect a drive or folder", { model.connectLocation() }, Modifier.padding(vertical = 8.dp))
    if (model.locations.isEmpty()) {
        Note("Nothing connected yet.")
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        model.locations.forEachIndexed { index, location ->
            val on = index == model.selectedLocation
            Text(
                location.label, fontSize = 13.sp, maxLines = 1,
                color = if (on) Palette.accent else Palette.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, if (on) Palette.accent else Palette.borderDefault), RoundedCornerShape(14.dp))
                    .clickable { model.selectedLocation = index }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
    val location = model.locations.getOrNull(model.selectedLocation) ?: return
    Row(Modifier.padding(vertical = 8.dp)) {
        SoftButton("Disconnect ${location.label}", { model.disconnectLocation(location) }, color = Palette.statusFailed)
    }
    val source = remember(location) { model.sourceFor(location) }
    Browser(source, model)
}

// -- GitHub -----------------------------------------------------------------------------------------------

@Composable
private fun GitHubTab(model: FilesModel) {
    var token by remember { mutableStateOf("") }
    val login = model.githubLogin
    if (login == null) {
        Note(
            "Link GitHub with a personal access token. Create a fine-grained token at " +
                "github.com/settings/tokens with Contents: Read and write for the repositories you want, " +
                "then paste it here. It is sealed with a key held in the phone's secure hardware and is only " +
                "ever sent to api.github.com."
        )
        TextField(
            token, { token = it }, singleLine = true,
            placeholder = { Text("github_pat_…  or  ghp_…") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.bgRaised, unfocusedContainerColor = Palette.bgRaised,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Palette.textPrimary, unfocusedTextColor = Palette.textPrimary,
            ),
        )
        ActionButton("Connect", { model.connectGithub(token); token = "" }, Modifier.padding(top = 8.dp), enabled = !model.busy)
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Signed in as $login", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        SoftButton("Disconnect", { model.disconnectGithub() }, color = Palette.statusFailed)
    }
    ActionButton("Link a repository", { model.chooseRepo() }, Modifier.padding(vertical = 8.dp), enabled = !model.busy)

    if (model.linked.isEmpty()) Note("No repositories linked yet.")
    model.linked.forEach { link -> RepoCard(link, model) }
}

@Composable
private fun RepoCard(link: Linked, model: FilesModel) {
    val pulled = remember(link, model.version) { model.isPulled(link) }
    val found = model.changes[link.key]
    Panel(Modifier.padding(bottom = 8.dp)) {
        Text(link.full, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("branch ${link.branch} · repos/${link.dir}", color = Palette.textTertiary, fontSize = 12.sp)
        Text(
            when {
                !pulled -> "Not pulled yet"
                found == null -> "Pulled"
                found.isEmpty() -> "Pulled · no local changes"
                else -> "Pulled · ${found.size} changed file${if (found.size == 1) "" else "s"}"
            },
            color = if (found?.isNotEmpty() == true) Palette.statusWarning else Palette.textSecondary,
            fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton(if (pulled) "Pull" else "Pull files", { model.pull(link) })
            if (pulled) {
                SoftButton("Changes", { model.checkChanges(link) })
                SoftButton("Push", { model.checkChanges(link, thenPush = true) }, color = Palette.accent)
                SoftButton("Open folder", { model.tab = 0; model.say("The pulled files are in repos/${link.dir}") })
            }
            SoftButton("Unlink", { model.unlink(link) }, color = Palette.statusFailed)
        }
        if (found?.isNotEmpty() == true) {
            Column(Modifier.padding(top = 8.dp)) { found.take(40).forEach { ChangeLine(it) } }
        }
    }
}

@Composable
private fun ChangeLine(change: Change) {
    val (tag, color) = when (change.kind) {
        Kind.ADDED -> "A" to Palette.statusSuccess
        Kind.MODIFIED -> "M" to Palette.statusWarning
        Kind.DELETED -> "D" to Palette.statusFailed
    }
    Row {
        Text(tag, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        Text(change.path, color = Palette.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FilesDialogs(model: FilesModel) {
    val close = { model.dialog = FilesDialog.NoDialog }
    when (val d = model.dialog) {
        FilesDialog.NoDialog -> Unit
        is FilesDialog.PickRepo -> {
            var query by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Link a repository") },
                text = {
                    Column {
                        TextField(query, { query = it }, singleLine = true, placeholder = { Text("Filter") })
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                            d.repos.filter { it.full.contains(query, ignoreCase = true) }.forEach { repo ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { model.chooseBranch(repo); close() }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(repo.full, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (repo.isPrivate) Text("private", color = Palette.textTertiary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
            )
        }
        is FilesDialog.PickBranch -> AlertDialog(
            onDismissRequest = close,
            title = { Text("${d.repo.full}: which branch?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    d.branches.forEach { branch ->
                        Text(
                            branch + if (branch == d.repo.defaultBranch) "  (default)" else "",
                            fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { model.link(d.repo, branch) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is FilesDialog.ConfirmPull -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Overwrite local changes?") },
            text = {
                Text(
                    "${d.changes} file${if (d.changes == 1) " has" else "s have"} changed on this phone since the last pull. " +
                        "Pulling replaces the folder with what is on GitHub, and those changes are lost. " +
                        "Push first if you want to keep them."
                )
            },
            confirmButton = {
                TextButton(onClick = { close(); model.pull(d.link, confirmed = true) }) {
                    Text("Overwrite", color = Palette.statusFailed)
                }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is FilesDialog.Push -> {
            var message by remember(d) { mutableStateOf("Update from Hermes Duplex (Android)") }
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Push to ${d.link.full}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("${d.changes.size} file${if (d.changes.size == 1) "" else "s"} will be committed to ${d.link.branch}.", fontSize = 13.sp)
                        Column(Modifier.padding(vertical = 8.dp)) { d.changes.take(40).forEach { ChangeLine(it) } }
                        if (d.changes.size > 40) Note("…and ${d.changes.size - 40} more")
                        TextField(message, { message = it }, label = { Text("Commit message") })
                    }
                },
                confirmButton = { TextButton(onClick = { model.push(d.link, message) }) { Text("Push") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
            )
        }
    }
}
