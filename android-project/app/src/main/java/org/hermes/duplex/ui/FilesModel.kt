package org.hermes.duplex.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.hermes.duplex.files.Change
import org.hermes.duplex.files.FileRegistry
import org.hermes.duplex.files.FileSource
import org.hermes.duplex.files.GitHubApi
import org.hermes.duplex.files.Linked
import org.hermes.duplex.files.LocalSource
import org.hermes.duplex.files.Node
import org.hermes.duplex.files.RepoInfo
import org.hermes.duplex.files.RepoSync
import org.hermes.duplex.files.SafSource
import java.io.File

/** Something copied, waiting to be pasted into a folder of any source. */
data class Clip(val source: FileSource, val node: Node)

sealed interface FilesDialog {
    data object NoDialog : FilesDialog
    data class PickRepo(val repos: List<RepoInfo>) : FilesDialog
    data class PickBranch(val repo: RepoInfo, val branches: List<String>) : FilesDialog
    data class ConfirmPull(val link: Linked, val changes: Int) : FilesDialog
    data class Push(val link: Linked, val changes: List<Change>) : FilesDialog
}

/**
 * The file manager's state.
 *
 * Long work (a pull, a push, a big copy) runs on a plain thread and reports
 * back through [message] -- progress while it runs, then the outcome, as a
 * sentence. Errors are never swallowed: the exception text is what the user is
 * shown, because "something went wrong" is not something anyone can act on.
 */
class FilesModel(private val activity: ComponentActivity) {
    private val ui = Handler(Looper.getMainLooper())
    val registry = FileRegistry(activity)
    val workspace = LocalSource(File(activity.filesDir, "workspace"))

    var tab by mutableIntStateOf(0)
    var locations by mutableStateOf(registry.locations())
        private set
    var selectedLocation by mutableIntStateOf(0)
    var clip by mutableStateOf<Clip?>(null)
    var message by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var dialog by mutableStateOf<FilesDialog>(FilesDialog.NoDialog)
    /** Bumped whenever files changed behind the browser's back, so it reloads. */
    var version by mutableIntStateOf(0)
        private set

    var githubLogin by mutableStateOf(registry.githubLogin)
        private set
    var linked by mutableStateOf(registry.linked())
        private set
    var changes by mutableStateOf<Map<String, List<Change>>>(emptyMap())
        private set

    // -- the browser's uploads -------------------------------------------------------

    private var uploadInto: Pair<FileSource, Node>? = null

    private val pickUploads = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val (source, dir) = uploadInto ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        task("Uploading ${uris.size} file${if (uris.size == 1) "" else "s"}…") {
            var done = 0
            for (uri in uris) {
                val name = nameOf(uri)
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    source.openWrite(dir, name).use { input.copyTo(it) }
                    done++
                }
            }
            "Uploaded $done file${if (done == 1) "" else "s"}"
        }
    }

    fun upload(source: FileSource, dir: Node) {
        uploadInto = source to dir
        pickUploads.launch(arrayOf("*/*"))
    }

    private fun nameOf(uri: Uri): String =
        runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"

    // -- connected locations ------------------------------------------------------------

    private val pickTree = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        // Persisted, so the grant survives a restart: without this the
        // location would silently stop working the next time the app opened.
        activity.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val label = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            activity.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "Storage"
        registry.addLocation(FileRegistry.Location(uri.toString(), label))
        locations = registry.locations()
        selectedLocation = locations.lastIndex
        say("Connected $label")
    }

    fun connectLocation() = pickTree.launch(null)

    /** Whether Google Drive's document provider is on this phone, i.e. will appear in the picker. */
    val driveInstalled: Boolean = runCatching {
        activity.packageManager.resolveContentProvider("com.google.android.apps.docs.storage", 0) != null
    }.getOrDefault(false)

    fun disconnectLocation(location: FileRegistry.Location) {
        runCatching {
            activity.contentResolver.releasePersistableUriPermission(
                Uri.parse(location.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        registry.removeLocation(location)
        locations = registry.locations()
        selectedLocation = 0
    }

    fun sourceFor(location: FileRegistry.Location): FileSource =
        SafSource(activity.contentResolver, Uri.parse(location.uri), location.label)

    // -- tasks ---------------------------------------------------------------------------

    fun say(text: String) {
        message = text
    }

    fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    /** Run [work] off the main thread. Its result is the success message. */
    fun task(start: String, work: () -> String) {
        if (busy) { say("Another task is still running"); return }
        busy = true
        message = start
        Thread {
            val result = runCatching(work)
            ui.post {
                busy = false
                message = result.fold({ it }, { it.message ?: it.javaClass.simpleName })
                version++
            }
        }.start()
    }

    private fun progress(text: String) = ui.post { message = text }

    // -- GitHub ----------------------------------------------------------------------------

    private fun api(): GitHubApi {
        val token = registry.githubToken() ?: throw java.io.IOException("Connect GitHub first")
        return GitHubApi(token)
    }

    private fun sync() = RepoSync(activity, api(), workspace)

    fun connectGithub(token: String) {
        if (token.isBlank()) { say("Paste a token first"); return }
        task("Checking the token…") {
            val login = GitHubApi(token).login()
            registry.saveGithubToken(token)
            registry.githubLogin = login
            ui.post { githubLogin = login }
            "Connected as $login"
        }
    }

    fun disconnectGithub() {
        registry.clearGithub()
        githubLogin = null
        say("GitHub disconnected. The token was erased from this phone.")
    }

    fun chooseRepo() {
        task("Loading your repositories…") {
            val repos = api().repos()
            ui.post { dialog = FilesDialog.PickRepo(repos) }
            "${repos.size} repositories"
        }
    }

    fun chooseBranch(repo: RepoInfo) {
        task("Loading branches…") {
            val branches = api().branches(repo.owner, repo.name)
                .sortedWith(compareByDescending<String> { it == repo.defaultBranch }.thenBy { it })
            ui.post { dialog = FilesDialog.PickBranch(repo, branches) }
            "${branches.size} branches"
        }
    }

    fun link(repo: RepoInfo, branch: String) {
        val folder = repo.full.replace('/', '-') + if (branch != repo.defaultBranch) "@" + branch.replace('/', '_') else ""
        registry.link(Linked(repo.owner, repo.name, branch, folder))
        linked = registry.linked()
        dialog = FilesDialog.NoDialog
        say("Linked ${repo.full} (${branch}). Pull it to copy the files onto this phone.")
    }

    fun unlink(link: Linked) {
        registry.unlink(link)
        runCatching { sync().forget(link) }
        linked = registry.linked()
        say("Unlinked ${link.full}. The files on this phone were kept.")
    }

    fun pull(link: Linked, confirmed: Boolean = false) {
        if (!confirmed) {
            task("Checking for local changes…") {
                val pending = sync().changes(link)
                if (pending.isEmpty()) {
                    val head = sync().pull(link, ::progress)
                    refreshChanges(link)
                    "Pulled ${link.full} at ${head.take(7)}"
                } else {
                    ui.post { dialog = FilesDialog.ConfirmPull(link, pending.size) }
                    "${pending.size} local change${if (pending.size == 1) "" else "s"} would be overwritten"
                }
            }
            return
        }
        task("Pulling ${link.full}…") {
            val head = sync().pull(link, ::progress)
            refreshChanges(link)
            "Pulled ${link.full} at ${head.take(7)}"
        }
    }

    fun checkChanges(link: Linked, thenPush: Boolean = false) {
        task("Looking for changes…") {
            val found = sync().changes(link)
            ui.post {
                changes = changes + (link.key to found)
                if (thenPush && found.isNotEmpty()) dialog = FilesDialog.Push(link, found)
            }
            if (found.isEmpty()) "No changes in ${link.full}" else "${found.size} changed file${if (found.size == 1) "" else "s"}"
        }
    }

    fun push(link: Linked, message: String) {
        dialog = FilesDialog.NoDialog
        task("Pushing ${link.full}…") {
            val commit = sync().push(link, message.ifBlank { "Update from Hermes Duplex (Android)" }, ::progress)
            refreshChanges(link)
            "Pushed ${commit.take(7)} to ${link.full} (${link.branch})"
        }
    }

    private fun refreshChanges(link: Linked) {
        val found = sync().changes(link)
        ui.post { changes = changes + (link.key to found) }
    }

    fun isPulled(link: Linked): Boolean = runCatching { sync().isPulled(link) }.getOrDefault(false)

    fun repoFolder(link: Linked): Node {
        val dir = RepoSync(activity, GitHubApi(""), workspace).folder(link)
        return Node(dir.absolutePath, dir.name, true)
    }
}
