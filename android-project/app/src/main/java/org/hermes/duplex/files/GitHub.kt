package org.hermes.duplex.files

import android.content.Context
import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class RepoInfo(val owner: String, val name: String, val isPrivate: Boolean, val defaultBranch: String) {
    val full get() = "$owner/$name"
}

/** A repository linked to a folder in the workspace. */
data class Linked(val owner: String, val repo: String, val branch: String, val dir: String) {
    val full get() = "$owner/$repo"
    val key get() = "${owner}__${repo}__${branch.replace('/', '_')}"
}

enum class Kind { ADDED, MODIFIED, DELETED }
data class Change(val path: String, val kind: Kind)

/**
 * GitHub over its REST API.
 *
 * There is no git binary on a phone and shipping a full git implementation to
 * move a few files would be most of this app's size again, so this does what
 * the web client did: the Git Data API. Pull is one zipball plus a tree
 * listing; push is blob -> tree -> commit -> ref. The token is a personal
 * access token, sent only to api.github.com as a bearer header and never
 * logged.
 */
class GitHubApi(token: String) {
    private val token = token.trim()
    private val http = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun request(method: String, path: String, body: JSONObject? = null): String {
        val url = if (path.startsWith("https://")) path else "https://api.github.com$path"
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        builder.method(
            method,
            if (body == null && method == "GET") null
            else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()),
        )
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(explain(response.code, text))
            return text
        }
    }

    /** A sentence meant to be read, not a status code. */
    private fun explain(code: Int, text: String): String {
        val message = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
        return when (code) {
            401 -> "GitHub rejected the token. It may have expired or been revoked."
            403 -> "GitHub refused: ${message.ifBlank { "the token does not have access to this" }}"
            404 -> "Not found, or this token has no access to it."
            409 -> message.ifBlank { "The repository is empty." }
            422 -> message.ifBlank { "GitHub could not apply that change." }
            else -> "GitHub error $code${if (message.isNotBlank()) ": $message" else ""}"
        }
    }

    fun login(): String = JSONObject(request("GET", "/user")).getString("login")

    fun repos(): List<RepoInfo> {
        val out = mutableListOf<RepoInfo>()
        for (page in 1..3) {
            val list = JSONArray(
                request("GET", "/user/repos?per_page=100&page=$page&sort=pushed" +
                    "&affiliation=owner,collaborator,organization_member")
            )
            for (i in 0 until list.length()) out += repoOf(list.getJSONObject(i))
            if (list.length() < 100) break
        }
        return out
    }

    fun repo(owner: String, name: String): RepoInfo =
        repoOf(JSONObject(request("GET", "/repos/$owner/$name")))

    private fun repoOf(json: JSONObject) = RepoInfo(
        owner = json.getJSONObject("owner").getString("login"),
        name = json.getString("name"),
        isPrivate = json.optBoolean("private"),
        defaultBranch = json.optString("default_branch", "main"),
    )

    fun branches(owner: String, name: String): List<String> {
        val list = JSONArray(request("GET", "/repos/$owner/$name/branches?per_page=100"))
        return (0 until list.length()).map { list.getJSONObject(it).getString("name") }
    }

    fun headSha(owner: String, name: String, branch: String): String =
        JSONObject(request("GET", "/repos/$owner/$name/git/ref/heads/${encode(branch)}"))
            .getJSONObject("object").getString("sha")

    private fun treeSha(owner: String, name: String, commit: String): String =
        JSONObject(request("GET", "/repos/$owner/$name/git/commits/$commit"))
            .getJSONObject("tree").getString("sha")

    /** path -> mode for every blob, so a push can keep executable bits. */
    fun modes(owner: String, name: String, commit: String): Map<String, String> {
        val tree = JSONObject(
            request("GET", "/repos/$owner/$name/git/trees/${treeSha(owner, name, commit)}?recursive=1")
        )
        val modes = HashMap<String, String>()
        val items = tree.optJSONArray("tree") ?: return modes
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("type") == "blob") modes[item.getString("path")] = item.getString("mode")
        }
        return modes
    }

    /** Download the repository at [commit] as a zip, to [into]. */
    fun downloadZip(owner: String, name: String, commit: String, into: File) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$name/zipball/$commit")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(explain(response.code, response.body?.string().orEmpty()))
            response.body!!.byteStream().use { input -> into.outputStream().use { input.copyTo(it) } }
        }
    }

    fun blob(owner: String, name: String, bytes: ByteArray): String =
        JSONObject(
            request("POST", "/repos/$owner/$name/git/blobs", JSONObject()
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("encoding", "base64"))
        ).getString("sha")

    fun commitTree(owner: String, name: String, parent: String, items: JSONArray, message: String): String {
        val base = treeSha(owner, name, parent)
        val tree = JSONObject(
            request("POST", "/repos/$owner/$name/git/trees",
                JSONObject().put("base_tree", base).put("tree", items))
        ).getString("sha")
        return JSONObject(
            request("POST", "/repos/$owner/$name/git/commits", JSONObject()
                .put("message", message).put("tree", tree).put("parents", JSONArray().put(parent)))
        ).getString("sha")
    }

    /** Fast-forward only: never force, so nobody's commits are overwritten. */
    fun moveBranch(owner: String, name: String, branch: String, sha: String) {
        request("PATCH", "/repos/$owner/$name/git/refs/heads/${encode(branch)}",
            JSONObject().put("sha", sha).put("force", false))
    }

    private fun encode(branch: String) = branch.split('/').joinToString("/") { Uri.encode(it) }
}

/** What GitHub had at the last pull or push, so changes can be told from the rest. */
private class Manifest(val commit: String, val entries: Map<String, Pair<String, String>>)

/**
 * Keeps one repository in step with a folder of the workspace.
 *
 * The folder is an ordinary folder: edit it in the file manager, drop files
 * into it, delete from it. What makes it a checkout is a manifest of the blob
 * hash of every file as GitHub had it, kept outside the folder so it never
 * shows up in a push. A change is simply a file whose hash differs.
 */
class RepoSync(context: Context, private val api: GitHubApi, private val workspace: LocalSource) {
    private val appContext = context.applicationContext
    private val manifests = File(appContext.filesDir, "sync").apply { mkdirs() }

    fun folder(link: Linked): File = workspace.dir("repos", link.dir)

    private fun manifestFile(link: Linked) = File(manifests, link.key + ".json")

    private fun load(link: Linked): Manifest? {
        val file = manifestFile(link).takeIf { it.isFile } ?: return null
        return runCatching {
            val json = JSONObject(file.readText())
            val entries = HashMap<String, Pair<String, String>>()
            val stored = json.getJSONObject("entries")
            for (path in stored.keys()) {
                val item = stored.getJSONObject(path)
                entries[path] = item.getString("sha") to item.getString("mode")
            }
            Manifest(json.getString("commit"), entries)
        }.getOrNull()
    }

    private fun save(link: Linked, manifest: Manifest) {
        val entries = JSONObject()
        manifest.entries.forEach { (path, pair) ->
            entries.put(path, JSONObject().put("sha", pair.first).put("mode", pair.second))
        }
        manifestFile(link).writeText(JSONObject().put("commit", manifest.commit).put("entries", entries).toString())
    }

    fun isPulled(link: Linked) = load(link) != null

    fun forget(link: Linked) {
        manifestFile(link).delete()
    }

    /** Every file under the folder, as a path relative to it using '/'. */
    private fun scan(root: File): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        root.walkTopDown().filter { it.isFile }.forEach {
            out[it.relativeTo(root).path.replace(File.separatorChar, '/')] = it
        }
        return out
    }

    fun changes(link: Linked): List<Change> {
        val manifest = load(link) ?: return emptyList()
        val local = scan(folder(link))
        val out = mutableListOf<Change>()
        local.forEach { (path, file) ->
            val known = manifest.entries[path]
            when {
                known == null -> out += Change(path, Kind.ADDED)
                known.first != blobSha(file) -> out += Change(path, Kind.MODIFIED)
            }
        }
        manifest.entries.keys.filter { it !in local }.forEach { out += Change(it, Kind.DELETED) }
        return out.sortedBy { it.path }
    }

    /**
     * Replace the folder with the branch as it is on GitHub now.
     *
     * Downloaded and unpacked beside the folder first, and only swapped in once
     * all of that has worked, so a dropped connection halfway cannot leave a
     * half-empty checkout. Callers are expected to have asked about local
     * changes: this overwrites them.
     */
    fun pull(link: Linked, progress: (String) -> Unit): String {
        progress("Finding the latest commit…")
        val head = api.headSha(link.owner, link.repo, link.branch)
        progress("Reading the file list…")
        val modes = api.modes(link.owner, link.repo, head)

        val zip = File(appContext.cacheDir, "pull-${link.key}.zip")
        val stage = File(appContext.cacheDir, "pull-${link.key}")
        try {
            progress("Downloading…")
            api.downloadZip(link.owner, link.repo, head, zip)
            stage.deleteRecursively(); stage.mkdirs()
            progress("Unpacking…")
            extract(zip, stage)

            val target = folder(link)
            target.deleteRecursively()
            if (!stage.renameTo(target)) {
                stage.copyRecursively(target, overwrite = true)
                stage.deleteRecursively()
            }
        } finally {
            zip.delete()
            stage.deleteRecursively()
        }

        // Built from what is actually on disk, so a file the zip left out (a
        // submodule, say) is simply absent from both sides and can never read
        // as "deleted locally" and be removed from the repository by a push.
        val entries = HashMap<String, Pair<String, String>>()
        scan(folder(link)).forEach { (path, file) ->
            entries[path] = blobSha(file) to (modes[path] ?: "100644")
        }
        save(link, Manifest(head, entries))
        return head
    }

    /** Commit every local change as one commit and move the branch to it. */
    fun push(link: Linked, message: String, progress: (String) -> Unit): String {
        val manifest = load(link) ?: throw IOException("Pull this repository first.")
        val changes = changes(link)
        if (changes.isEmpty()) throw IOException("Nothing to push: no files have changed.")

        progress("Checking the branch…")
        val head = api.headSha(link.owner, link.repo, link.branch)
        if (head != manifest.commit) {
            throw IOException("This branch has new commits on GitHub. Pull first, then push again.")
        }

        val root = folder(link)
        val items = JSONArray()
        val updated = HashMap(manifest.entries)
        val total = changes.count { it.kind != Kind.DELETED }
        var done = 0
        for (change in changes) {
            if (change.kind == Kind.DELETED) {
                items.put(JSONObject().put("path", change.path).put("mode", "100644")
                    .put("type", "blob").put("sha", JSONObject.NULL))
                updated.remove(change.path)
                continue
            }
            progress("Uploading ${++done}/$total: ${change.path}")
            val file = File(root, change.path)
            val bytes = file.readBytes()
            val sha = api.blob(link.owner, link.repo, bytes)
            val mode = manifest.entries[change.path]?.second ?: "100644"
            items.put(JSONObject().put("path", change.path).put("mode", mode)
                .put("type", "blob").put("sha", sha))
            updated[change.path] = blobSha(file) to mode
        }

        progress("Committing…")
        val commit = api.commitTree(link.owner, link.repo, head, items, message)
        api.moveBranch(link.owner, link.repo, link.branch, commit)
        save(link, Manifest(commit, updated))
        return commit
    }

    private fun extract(zip: File, into: File) {
        val rootPath = into.canonicalPath
        var bytes = 0L
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                // GitHub wraps everything in one "owner-repo-sha/" folder.
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty() || entry.isDirectory) continue
                val out = File(into, relative).canonicalFile
                // A zip entry named "../x" must not write outside the folder.
                if (!out.path.startsWith(rootPath + File.separator)) {
                    throw IOException("The download contained an unsafe path")
                }
                if (++count > 50_000) throw IOException("This repository has too many files for the phone")
                out.parentFile?.mkdirs()
                out.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        bytes += n
                        if (bytes > 400L * 1024 * 1024) throw IOException("This repository is too large for the phone")
                        sink.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    /** Git's own hash of a file: sha1("blob <size>\0" + content). */
    private fun blobSha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray())
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What is linked and connected, remembered across launches. */
class FileRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hermes-files", Context.MODE_PRIVATE)

    // -- GitHub account ------------------------------------------------------

    var githubLogin: String?
        get() = prefs.getString("gh_login", null)
        set(value) { prefs.edit().putString("gh_login", value).apply() }

    fun githubToken(): String? = SecretStore.get(appContext, "github_token")
    fun saveGithubToken(token: String) = SecretStore.put(appContext, "github_token", token.trim())
    fun clearGithub() {
        SecretStore.remove(appContext, "github_token")
        githubLogin = null
    }

    fun linked(): List<Linked> {
        val raw = prefs.getString("linked", null) ?: return emptyList()
        return runCatching {
            val list = JSONArray(raw)
            (0 until list.length()).map {
                val item = list.getJSONObject(it)
                Linked(item.getString("owner"), item.getString("repo"),
                    item.getString("branch"), item.getString("dir"))
            }
        }.getOrDefault(emptyList())
    }

    fun link(repo: Linked) {
        val all = linked().filterNot { it.key == repo.key } + repo
        prefs.edit().putString("linked", JSONArray(all.map {
            JSONObject().put("owner", it.owner).put("repo", it.repo)
                .put("branch", it.branch).put("dir", it.dir)
        }).toString()).apply()
    }

    fun unlink(repo: Linked) {
        val all = linked().filterNot { it.key == repo.key }
        prefs.edit().putString("linked", JSONArray(all.map {
            JSONObject().put("owner", it.owner).put("repo", it.repo)
                .put("branch", it.branch).put("dir", it.dir)
        }).toString()).apply()
    }

    // -- connected storage locations (Drive, USB, SD...) -----------------------

    data class Location(val uri: String, val label: String)

    fun locations(): List<Location> {
        val raw = prefs.getString("locations", null) ?: return emptyList()
        return runCatching {
            val list = JSONArray(raw)
            (0 until list.length()).map {
                val item = list.getJSONObject(it)
                Location(item.getString("uri"), item.getString("label"))
            }
        }.getOrDefault(emptyList())
    }

    fun addLocation(location: Location) {
        val all = locations().filterNot { it.uri == location.uri } + location
        saveLocations(all)
    }

    fun removeLocation(location: Location) = saveLocations(locations().filterNot { it.uri == location.uri })

    private fun saveLocations(all: List<Location>) {
        prefs.edit().putString("locations", JSONArray(all.map {
            JSONObject().put("uri", it.uri).put("label", it.label)
        }).toString()).apply()
    }
}
