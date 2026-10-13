package org.hermes.duplex.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.hermes.duplex.ConversationStore
import org.hermes.duplex.DuplexClient
import org.hermes.duplex.ConversationStore.Role
import org.hermes.duplex.R
import org.hermes.duplex.ui.theme.Palette
import java.net.HttpURLConnection
import java.net.URL

/**
 * One row of the thread, in AgentKit's shape.
 *
 * `You` is a bubble on the right, capped at 78% of the width. `Agent` is
 * plain full-width text with no background -- the asymmetry is the web's, and
 * deliberate: replies run long, and a bubble drawn around several paragraphs
 * reads as a wall rather than as speech.
 */
@Composable
fun MessageItem(message: ConversationStore.Message, session: MainSession) {
    when (message.role) {
        Role.SYSTEM -> NoteRow(message)
        Role.IDEA -> IdeaRow(message, session)
        else -> TurnRow(message, session)
    }
}

/** The thread commenting on itself: centred, dim, and unattributed. */
@Composable
private fun NoteRow(message: ConversationStore.Message) {
    Text(
        text = message.text,
        color = Palette.textTertiary,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 12.dp),
    )
}

@Composable
private fun TurnRow(message: ConversationStore.Message, session: MainSession) {
    val you = message.role == Role.YOU
    val speaking = session.speakingKey == message.speechKey
    val width = LocalConfiguration.current.screenWidthDp.dp

    val card = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            // The agent's reply sits in a card -- a long answer reads as one
            // block with edges, instead of text running into the thread. Your
            // own words stay a bubble on the right.
            .let { base ->
                if (you) base
                else base
                    .clip(card)
                    .background(Palette.bgRaised)
                    .border(androidx.compose.foundation.BorderStroke(1.dp, Palette.borderDefault), card)
                    .padding(14.dp)
            },
        horizontalAlignment = if (you) Alignment.End else Alignment.Start,
    ) {
        // Role label and the row's actions share a line.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(if (you) R.string.speaker_you else R.string.speaker_agent),
                color = Palette.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 6.dp),
            )
            Glyph(R.drawable.ic_copy, "Copy message") {
                session.copyMessage(message)
            }
            // Rewrite is yours to do; the agent's turn is not something to edit.
            if (you) Glyph(R.drawable.ic_edit, "Rewrite this message") { session.rewriteMessage(message) }
            // Reading aloud is for the agent's words, not your own.
            if (!you) {
                Glyph(
                    if (speaking) R.drawable.ic_pause else R.drawable.ic_play,
                    stringResource(if (speaking) R.string.pause_message else R.string.read_message),
                ) { session.speakMessage(message) }
            }
            // The lightbulb appears only where there is something behind it.
            if (message.suggestions.isNotEmpty()) {
                Glyph(R.drawable.ic_idea, "Bring back this reply's suggestions", Palette.accent) {
                    session.restoreSuggestions(message)
                }
            }
        }

        MediaBlock(message)

        // Markdown is rendered even though the system prompt forbids it: the
        // model emits it anyway, and `*3 Body Problem*` showing its asterisks
        // is what an instruction-as-rendering-strategy looks like.
        if (you) {
            // Files sent with the message are shown as what they are -- an
            // image as its picture -- rather than as the paths the agent was
            // given. The text the agent receives is unchanged.
            val parsed = remember(message.text) { parseAttachments(message.text) }
            if (parsed.body.isNotEmpty()) {
                Box(
                    Modifier
                        .widthIn(max = width * 0.78f)
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Palette.userBubble)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    MarkdownText(
                        text = parsed.body,
                        italic = true,
                        fillWidth = false,
                    )
                }
            }
            if (parsed.paths.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    parsed.paths.forEach { path -> SentAttachment(path) }
                }
            }
        } else {
            MarkdownText(
                text = message.text,
                streaming = message.streaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }

        val timing = message.timing
        val meta = when {
            message.interrupted && timing != null -> stringResource(
                R.string.meta_both, timing, stringResource(R.string.msg_interrupted)
            )
            message.interrupted -> stringResource(R.string.msg_interrupted)
            else -> timing
        }
        if (meta != null) {
            Text(
                text = meta,
                color = if (message.interrupted) Palette.statusWarning else Palette.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        DetailBlock(message, session)
    }
}

/** A small tappable symbol, with a spoken label. */
@Composable
private fun Glyph(
    @androidx.annotation.DrawableRes icon: Int,
    description: String,
    color: Color = Palette.textTertiary,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) { Ico(icon, tint = color, size = 16.dp) }
}

// -- the steps behind a CLI turn ------------------------------------------------------------

/**
 * The closed marker under a reply, and its contents once opened.
 *
 * Only ever present on a turn from a CLI transcript: nothing said in this app
 * has steps behind it. The open state lives on the message, not in the row,
 * so a recycled row can never show the previous message's steps.
 */
@Composable
private fun DetailBlock(message: ConversationStore.Message, session: MainSession) {
    if (message.detail <= 0) return
    val open = message.stepsOpen && message.steps.isNotEmpty()
    val label = when {
        message.detailLoading -> stringResource(R.string.cli_loading)
        message.detail == 1 -> stringResource(R.string.cli_detail_one, message.detail)
        else -> stringResource(R.string.cli_detail_many, message.detail)
    }
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { session.toggleDetail(message) }
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (open) Palette.accent else Palette.textTertiary
        Ico(if (open) R.drawable.ic_expand_more else R.drawable.ic_chevron_right, tint = tint, size = 16.dp)
        Text(text = label, color = tint, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
    }
    if (!open) return
    Column(Modifier.fillMaxWidth()) {
        message.steps.forEachIndexed { index, step ->
            StepRow(step, loading = "${message.turnId}#$index" in session.outputsLoading) { session.toggleStep(message, index) }
        }
    }
}

/**
 * One step: what ran, and -- on a second tap -- what it answered. Two levels,
 * as the Claude app has it: skimming the list is one tap, reading what a
 * single `Read` returned is another.
 */
@Composable
private fun StepRow(step: ConversationStore.Step, loading: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.bgApp)
    ) {
        // The tool names what happened; the subject says to what. Both on one
        // line, because the point of the closed list is to be skimmed.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (step.expanded) Palette.accent else Palette.textSecondary
            Ico(if (step.expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right, tint = tint, size = 14.dp)
            Text(
                text = buildString {
                    append(step.tool)
                    if (step.subject.isNotEmpty()) append("  ").append(step.subject)
                },
                color = tint,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (step.expanded) {
            SelectionContainer {
                Column(Modifier.padding(horizontal = 10.dp)) {
                    // What was actually run, in full, above what it returned.
                    // The closed row cuts it to fit one line; a command worth
                    // checking is precisely the long one.
                    if (step.command.isNotBlank()) {
                        Text("RUN", color = Palette.textSecondary, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            step.command, color = Palette.accent,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Text(
                        text = "OUTPUT\n" + step.output.ifBlank {
                            // Not yet fetched is not the same as silent.
                            if (loading) "Loading…" else stringResource(R.string.cli_step_silent)
                        },
                        color = Palette.textTertiary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
        }
    }
}

// -- media ---------------------------------------------------------------------------------------

private enum class MediaKind { IMAGE, VIDEO, FILE }
private data class MediaLink(val url: String, val kind: MediaKind, val preview: String = "")

private val URL_PATTERN = Regex("(?:https?://|www\\.)[^\\s<>()\\]\"']+")
private val IMAGE_EXT = Regex(".*\\.(png|jpe?g|gif|webp|bmp|svg)$")
private val VIDEO_EXT = Regex(".*\\.(mp4|webm|mov|m4v|m3u8)$")
private val FILE_EXT = Regex(".*\\.(pdf|zip|tar|gz|apk|docx?|xlsx?|pptx?|txt|csv)$")

private fun mediaLinks(text: String): List<MediaLink> =
    URL_PATTERN.findAll(text)
        .map { m ->
            m.value.trimEnd('.', ',', ';', ':', '!', '?')
                .let { if (it.startsWith("www.")) "https://$it" else it }
        }
        .distinct()
        .mapNotNull { url ->
            val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
            val kind = when {
                path.matches(IMAGE_EXT) -> MediaKind.IMAGE
                path.matches(VIDEO_EXT) -> MediaKind.VIDEO
                path.matches(FILE_EXT) -> MediaKind.FILE
                else -> null
            }
            kind?.let { MediaLink(url, it) }
        }
        .toList()

/**
 * Make search results useful without requiring the model to describe an
 * image or file instead of returning it. The text stays visible and cited;
 * recognised URLs additionally get an actual in-chat affordance.
 */
@Composable
private fun MediaBlock(message: ConversationStore.Message) {
    val structured = message.media.flatMap { row ->
        when {
            row.video.isNotBlank() -> listOf(MediaLink(row.video, MediaKind.VIDEO))
            row.image.isNotBlank() -> listOf(MediaLink(row.image, MediaKind.IMAGE, row.thumbnail))
            else -> emptyList()
        }
    }
    val media = remember(message.text, message.media) {
        (structured + mediaLinks(message.text)).distinctBy { it.url }
    }
    if (media.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        media.forEach { item ->
            when (item.kind) {
                MediaKind.IMAGE -> RemoteImage(item.url, item.preview)
                MediaKind.VIDEO -> VideoPlayer(item.url)
                MediaKind.FILE -> DownloadButton(item.url)
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String, preview: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, url, preview) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(preview.ifBlank { url }).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", "HermesDuplex/1.0")
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.bgRaised)
            .semantics { contentDescription = "Search result image" }
            .clickable { openUrl(context, url) },
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        }
    }
}

@Composable
private fun VideoPlayer(url: String) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp)),
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(Uri.parse(url))
                setMediaController(MediaController(context))
            }
        },
    )
}

@Composable
private fun DownloadButton(url: String) {
    val context = LocalContext.current
    SoftButton("Download file", onClick = {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(Uri.parse(url).lastPathSegment ?: "Downloaded file")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.onFailure {
            Toast.makeText(context, "Unable to download file", Toast.LENGTH_SHORT).show()
        }
    })
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "Unable to open result", Toast.LENGTH_SHORT).show()
    }
}

// -- private ideas ----------------------------------------------------------------------------------

private data class IdeaStep(val title: String, val detail: String)

/** Parse the compact flow format, with paragraph fallback for old ideas. */
private fun parseIdea(raw: String): Pair<String, List<IdeaStep>> {
    val nonEmpty = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val title = (nonEmpty.firstOrNull() ?: "Private project idea")
        .replace(Regex("^#{1,6}\\s*"), "")
        .trim()
    val numbered = Regex("^\\s*(?:\\d+[.)]|[-*])\\s+(.+)$")
    val found = mutableListOf<IdeaStep>()
    var currentTitle = ""
    val currentDetail = StringBuilder()
    fun flush() {
        if (currentTitle.isNotBlank()) {
            found += IdeaStep(currentTitle, currentDetail.toString().trim())
        }
        currentDetail.clear()
    }
    nonEmpty.drop(1).forEach { line ->
        val match = numbered.find(line)
        if (match != null) {
            flush()
            val parts = match.groupValues[1].split(" — ", limit = 2)
            currentTitle = parts.first().trim()
            if (parts.size > 1) currentDetail.append(parts[1].trim())
        } else if (currentTitle.isNotBlank()) {
            if (currentDetail.isNotEmpty()) currentDetail.append(" ")
            currentDetail.append(line)
        } else {
            currentTitle = "Next direction"
            currentDetail.append(line)
        }
    }
    flush()
    if (found.isEmpty()) {
        raw.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.drop(1)
            .forEachIndexed { index, text -> found += IdeaStep("Direction ${index + 1}", text) }
    }
    return title to (found.ifEmpty { listOf(IdeaStep("Review the idea", raw)) })
}

/**
 * Private local-LLM advice: visually distinct and never an agent turn.
 *
 * Compact by default and opened only on tap, so a long idea never becomes a
 * wall of text in the mobile chat. Touching it marks the session so the list
 * knows not to collapse it in the same gesture.
 */
@Composable
private fun IdeaRow(message: ConversationStore.Message, session: MainSession) {
    val flow = remember(message.text) { parseIdea(message.text) }
    val speaking = session.speakingKey == message.speechKey
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    session.ideaTouched = true
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Private idea",
                color = Palette.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 6.dp),
            )
            Glyph(R.drawable.ic_copy, "Copy message") { session.copyMessage(message) }
            Glyph(
                if (speaking) R.drawable.ic_pause else R.drawable.ic_play,
                stringResource(if (speaking) R.string.pause_message else R.string.read_message),
            ) { session.speakMessage(message) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.ideaBackground)
                .clickable { session.toggleIdea(message) }
                .semantics {
                    contentDescription = if (message.ideaOpen) {
                        "Private project idea. Tap to collapse."
                    } else {
                        "Collapsed private project idea. Tap to expand."
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // The header is always compact; the vertical stepper carries the
            // expanded detail.
            Text(
                text = flow.first,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = if (message.ideaOpen) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (message.ideaOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.ideaBackground)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                flow.second.forEachIndexed { index, step ->
                    IdeaStepRow(step, index, index == flow.second.lastIndex) {
                        session.requestIdeaSend(step.detail)
                    }
                }
            }
        }
    }
}

@Composable
private fun IdeaStepRow(step: IdeaStep, index: Int, last: Boolean, onSend: () -> Unit) {
    val dot = when (index % 3) {
        0 -> Palette.stateHearing
        1 -> Palette.stateThinking
        else -> Palette.statusWarning
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(top = 4.dp, bottom = if (last) 0.dp else 10.dp),
    ) {
        Column(Modifier.width(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 4.dp).size(10.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
            if (!last) {
                Box(
                    Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(Palette.accentMuted)
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${index + 1}. ${step.title}",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            MarkdownText(
                text = step.detail,
                color = Color(0xFFD7E4F5),
                sizeSp = 12f,
                modifier = Modifier.padding(top = 3.dp, bottom = 5.dp),
            )
            Text(
                "Send next step to AI",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .heightIn(min = 30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Palette.accent)
                    .clickable(onClick = onSend)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}


// -- files sent with a message -----------------------------------------------------------------

private data class SentFiles(val body: String, val paths: List<String>)

/** The block [MainSession] appends when files go with a message. */
private val ATTACHED = Regex(
    "\\n\\nAttached files? \\(saved on this machine; read them from these paths\\):((?:\\n- .+)+)\\s*$"
)

private fun parseAttachments(text: String): SentFiles {
    val found = ATTACHED.find(text) ?: return SentFiles(text, emptyList())
    val paths = found.groupValues[1].lines().map { it.removePrefix("- ").trim() }.filter { it.isNotEmpty() }
    val body = text.substring(0, found.range.first).trim()
    // The placeholder the app writes when you send a file with no words.
    return SentFiles(if (body.startsWith("(see the attached")) "" else body, paths)
}

private fun bridgeFileUrl(path: String): String {
    val base = DuplexClient.lastUrl
        .replaceFirst("ws://", "http://").replaceFirst("wss://", "https://").removeSuffix("/ws")
    return base.toHttpUrl().newBuilder()
        .addPathSegments("uploads/file").addQueryParameter("path", path).build().toString()
}

private val IMAGE_PATH = Regex(".*\\.(png|jpe?g|gif|webp|bmp)$", RegexOption.IGNORE_CASE)

/** One sent file: its picture if it is an image, otherwise its name. */
@Composable
private fun SentAttachment(path: String) {
    val context = LocalContext.current
    val name = path.substringAfterLast('/')
    if (!path.matches(IMAGE_PATH)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.userBubble)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(R.drawable.ic_file, tint = Palette.textSecondary, size = 18.dp)
            Text(name, color = Palette.textPrimary, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 200.dp))
        }
        return
    }
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = (URL(bridgeFileUrl(path)).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 15000
                    inputStream.use { it.readBytes() }
                }
                // Decoded at thumbnail size: a full-resolution screenshot is
                // several times the memory of what is drawn.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 480 && bounds.outHeight / (sample * 2) >= 480) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(width = 160.dp, height = 160.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.bgRaised)
            .semantics { contentDescription = "Attached image $name" }
            .clickable { openUrl(context, bridgeFileUrl(path)) },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } ?: Ico(R.drawable.ic_file, tint = Palette.textTertiary, size = 28.dp)
    }
}
