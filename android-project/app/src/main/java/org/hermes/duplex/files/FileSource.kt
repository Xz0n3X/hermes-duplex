package org.hermes.duplex.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One entry in a folder. [id] is whatever the source needs to find it again. */
data class Node(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L,
    val mime: String = "",
)

/**
 * A place files live: the app's own workspace, or any folder the user has
 * granted through Android's picker (Google Drive, a USB drive, an SD card...).
 *
 * One interface for both, so the browser, copy and paste between them, and the
 * GitHub sync all work the same on either. Nothing here throws on a missing
 * file for its own sake: callers get an [IOException] with a sentence that can
 * be shown as it is.
 */
interface FileSource {
    val label: String
    val root: Node
    fun list(dir: Node): List<Node>
    fun mkdir(dir: Node, name: String): Node
    fun openRead(node: Node): InputStream
    /** Create [name] in [dir], replacing a file of that name if there is one. */
    fun openWrite(dir: Node, name: String, mime: String = "application/octet-stream"): OutputStream
    fun delete(node: Node)
    fun rename(node: Node, newName: String): Node
    /** A uri another app can open, or null if there is no safe way to give one. */
    fun shareUri(context: Context, node: Node): Uri?
}

/** The app's private workspace. Everything pulled from GitHub lands here. */
class LocalSource(private val base: File) : FileSource {
    override val label = "This phone"
    private val rootDir = base.apply { mkdirs() }.canonicalFile
    override val root = node(rootDir).copy(name = "Workspace")

    private fun node(file: File) = Node(
        id = file.absolutePath, name = file.name, isDir = file.isDirectory,
        size = if (file.isFile) file.length() else 0L, modified = file.lastModified(),
    )

    /** Never lets a name or an id reach outside the workspace. */
    private fun inside(file: File): File {
        val canonical = file.canonicalFile
        if (canonical != rootDir && !canonical.path.startsWith(rootDir.path + File.separator)) {
            throw IOException("That path is outside the workspace")
        }
        return canonical
    }

    private fun clean(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == ".." || '/' in trimmed) {
            throw IOException("“$name” is not a valid name")
        }
        return trimmed
    }

    override fun list(dir: Node): List<Node> =
        inside(File(dir.id)).listFiles().orEmpty().map(::node)

    override fun mkdir(dir: Node, name: String): Node {
        val folder = inside(File(inside(File(dir.id)), clean(name)))
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Could not create the folder")
        return node(folder)
    }

    override fun openRead(node: Node): InputStream = inside(File(node.id)).inputStream()

    override fun openWrite(dir: Node, name: String, mime: String): OutputStream =
        inside(File(inside(File(dir.id)), clean(name))).outputStream()

    override fun delete(node: Node) {
        val file = inside(File(node.id))
        if (file == rootDir) throw IOException("The workspace itself cannot be deleted")
        if (!file.deleteRecursively()) throw IOException("Could not delete ${file.name}")
    }

    override fun rename(node: Node, newName: String): Node {
        val file = inside(File(node.id))
        val target = inside(File(file.parentFile, clean(newName)))
        if (target.exists()) throw IOException("“${target.name}” already exists")
        if (!file.renameTo(target)) throw IOException("Could not rename ${file.name}")
        return node(target)
    }

    override fun shareUri(context: Context, node: Node): Uri? {
        val file = inside(File(node.id))
        if (!file.isFile) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** The workspace folder for a path relative to it, created if needed. */
    fun dir(vararg parts: String): File =
        parts.fold(rootDir) { acc, part -> File(acc, part) }.apply { mkdirs() }
}

/**
 * A folder the user granted through the system picker.
 *
 * This is how Google Drive, a USB drive or an SD card are connected, and it is
 * deliberate that none of them needs an account, a client id or a token in this
 * app: the Drive app (or the drive's own provider) does the signing in, and
 * hands this app exactly the one folder that was chosen, nothing else.
 */
class SafSource(
    private val resolver: ContentResolver,
    private val tree: Uri,
    override val label: String,
) : FileSource {

    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    override val root = Node(rootId, label, true, mime = DocumentsContract.Document.MIME_TYPE_DIR)

    private fun doc(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun list(dir: Node): List<Node> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.id)
        val out = mutableListOf<Node>()
        val cursor = resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        ) ?: throw IOException("This location is not available right now")
        cursor.use {
            while (it.moveToNext()) {
                val mime = it.getString(2).orEmpty()
                out += Node(
                    id = it.getString(0), name = it.getString(1).orEmpty(),
                    isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (it.isNull(3)) 0L else it.getLong(3),
                    modified = if (it.isNull(4)) 0L else it.getLong(4),
                    mime = mime,
                )
            }
        }
        return out
    }

    override fun mkdir(dir: Node, name: String): Node {
        val created = DocumentsContract.createDocument(
            resolver, doc(dir.id), DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: throw IOException("This location does not allow new folders")
        return Node(DocumentsContract.getDocumentId(created), name, true)
    }

    override fun openRead(node: Node): InputStream =
        resolver.openInputStream(doc(node.id)) ?: throw IOException("Could not open ${node.name}")

    override fun openWrite(dir: Node, name: String, mime: String): OutputStream {
        // Overwrite in place when the name exists, so a re-upload does not
        // pile up "name (1)" copies in a provider that renames collisions.
        val existing = list(dir).firstOrNull { it.name == name && !it.isDir }
        val target = if (existing != null) doc(existing.id)
        else DocumentsContract.createDocument(resolver, doc(dir.id), mime, name)
            ?: throw IOException("This location does not allow new files")
        return resolver.openOutputStream(target, "wt") ?: throw IOException("Could not write $name")
    }

    override fun delete(node: Node) {
        if (!DocumentsContract.deleteDocument(resolver, doc(node.id))) {
            throw IOException("This location did not allow deleting ${node.name}")
        }
    }

    override fun rename(node: Node, newName: String): Node {
        val renamed = DocumentsContract.renameDocument(resolver, doc(node.id), newName)
            ?: throw IOException("This location did not allow renaming ${node.name}")
        return node.copy(id = DocumentsContract.getDocumentId(renamed), name = newName)
    }

    override fun shareUri(context: Context, node: Node): Uri? = if (node.isDir) null else doc(node.id)
}

/** Copy [node] from one source into [intoDir] of another, never overwriting. */
fun copyNode(from: FileSource, node: Node, to: FileSource, intoDir: Node) {
    val taken = to.list(intoDir).map { it.name }.toSet()
    val name = uniqueName(node.name, taken)
    if (node.isDir) {
        val made = to.mkdir(intoDir, name)
        from.list(node).forEach { copyNode(from, it, to, made) }
    } else {
        from.openRead(node).use { input ->
            to.openWrite(intoDir, name, node.mime.ifBlank { "application/octet-stream" }).use { out ->
                input.copyTo(out)
            }
        }
    }
}

/** "notes.txt" -> "notes (copy).txt" -> "notes (copy 2).txt" */
fun uniqueName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val stem = name.substring(0, dot)
    val ext = name.substring(dot)
    var n = 1
    while (true) {
        val candidate = "$stem (copy${if (n > 1) " $n" else ""})$ext"
        if (candidate !in taken) return candidate
        n++
    }
}
