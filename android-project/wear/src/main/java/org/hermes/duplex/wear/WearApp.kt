package org.hermes.duplex.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import org.hermes.duplex.OrbModel
import org.hermes.duplex.PlainText
import org.hermes.duplex.Speaker
import kotlinx.coroutines.launch

/**
 * Two screens, and no more.
 *
 * The talk screen is the app; the chat list exists because a reply only
 * means something in the conversation it belongs to. Everything else the
 * phone can do stays on the phone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WearApp(
    model: WearState,
    onTalk: () -> Unit,
    onSuggestion: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onEngine: (String) -> Unit,
    onRepeat: () -> Unit,
    onApprove: (String, Boolean) -> Unit,
    settings: WearSettings,
    onEditBridge: () -> Unit,
) {
    // Three pages rather than a nav host.
    //
    // On a watch the horizontal swipe is the gesture people already have in
    // their hands, and a nav host spends it on "back". Talking is the middle
    // page because it is what the app is for; the other two are things you
    // set once and swipe away from.
    val pager = rememberPagerState(initialPage = TALK) { 4 }
    val scope = rememberCoroutineScope()

    // Picking a conversation is only ever a way of getting into it, so the
    // list hands the pager back to the talk page rather than leaving you on
    // a screen you are done with, swiping to where you meant to end up.
    val toTalk = { scope.launch { pager.animateScrollToPage(TALK) }; Unit }

    MaterialTheme {
        // A question the agent is blocked on takes the whole screen, over
        // whatever page you were on. It is not a page of its own: there is
        // nothing to browse, one thing is waiting, and swiping past it would
        // be a way of accidentally denying it three minutes later.
        if (model.approvalId.isNotEmpty()) {
            ApprovalScreen(
                tool = model.approvalTool,
                subject = model.approvalSubject,
                onAnswer = { allow -> onApprove(model.approvalId, allow) },
            )
            return@MaterialTheme
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                CHATS -> ChatsScreen(
                    model = model,
                    onNewChat = { onNewChat(); toTalk() },
                    onOpenChat = { id -> onOpenChat(id); toTalk() },
                    onEnterChat = toTalk,
                    onOpenProject = onOpenProject,
                    onDeleteProject = onDeleteProject,
                )
                TALK -> TalkScreen(
                    model = model,
                    onTalk = onTalk,
                    onSuggestion = onSuggestion,
                    onStop = onStop,
                    onRepeat = onRepeat,
                    settings = settings,
                )
                THREAD -> ThreadScreen(model = model)
                else -> SettingsScreen(
                    model = model,
                    onEngine = onEngine,
                    settings = settings,
                    onEditBridge = onEditBridge,
                )
            }
        }
    }
}



/**
 * May it run this?
 *
 * The command is the screen. Everything else here is one word of context and
 * two buttons, because the whole of the decision is whether that line is
 * something you meant to happen -- and a wrist has room for the line or for
 * an explanation of the line, not both.
 *
 * Deny is the wider, closer target. If the two are equally easy to hit at
 * arm's length while walking, then the safe answer and the unsafe one cost
 * the same, and the prompt is decoration.
 */
@Composable
private fun ApprovalScreen(
    tool: String,
    subject: String,
    onAnswer: (Boolean) -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                text = "$tool wants to run",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = subject,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        item {
            Chip(
                label = { Text("Deny") },
                onClick = { onAnswer(false) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            CompactChip(
                label = { Text("Allow once") },
                onClick = { onAnswer(true) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Text(
                text = "No answer means no.",
                style = MaterialTheme.typography.caption3,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Swipe right for what you are talking about, left for how it connects. */
private const val CHATS = 0
private const val TALK = 1

/**
 * The transcript, one quick swipe left of the face.
 *
 * The talk screen answers "what is it saying now" and is built around the
 * live exchange. Reading back over what was said is a different job, and
 * doing it there meant scrolling up through a list that rescrolls itself
 * every time a block lands. This page does not move under you.
 */
private const val THREAD = 2

/** The name shown on the watch for the engine currently answering. */
private fun engineLabel(id: String): String = when (id.lowercase()) {
    "codex" -> "Codex"
    "claude" -> "Claude"
    else -> "Hermes"
}

/** A stable identity color; the reply text itself remains neutral and readable. */
private fun engineColor(id: String): Color = when (id.lowercase()) {
    "codex" -> Color(0xFF9FA8FF)
    "claude" -> Color(0xFFFFC266)
    else -> Color(0xFF66D9D0)
}

/** Keep status text tied to the selected engine instead of calling everything Hermes. */
private fun engineStatus(engine: String, status: String): String {
    if (status.isBlank()) return status
    if (status == "Phone is not connected to Hermes") return status
    return "${engineLabel(engine)} · $status"
}

/**
 * Talk, and see what came back.
 *
 * The orb fills the screen and the content sits over it, which is the same
 * arrangement the phone settled on: on a round display a boxed animation with
 * a caption underneath wastes the corners.
 *
 * The content is a ScalingLazyColumn rather than a scrolling Column. Two
 * reasons, and the second is the one that mattered: it scrolls with the
 * rotary bezel as well as the finger, and a plain Column under a full-screen
 * clickable did not reliably scroll at all -- so a long reply buried the
 * suggestion chips under the bottom of the watch with no way to reach them.
 *
 * Talking is a chip now rather than the whole face. A face that both scrolls
 * and starts a recording has to guess which one a drag meant, and it guessed
 * wrong often enough to lose the chips entirely.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TalkScreen(
    model: WearState,
    onTalk: () -> Unit,
    onSuggestion: (String) -> Unit,
    onStop: () -> Unit,
    onRepeat: () -> Unit,
    settings: WearSettings,
) {
    val listState = rememberScalingLazyListState()
    // The bridge can remain in SPEAKING while this watch is silent (another
    // client may be reading the answer). That state is not something the
    // watch can stop, so only expose Stop for work this device can actually
    // interrupt.
    val busy = model.listening ||
        model.turn == org.hermes.duplex.WearLink.Turn.THINKING ||
        model.turn == org.hermes.duplex.WearLink.Turn.WORKING ||
        org.hermes.duplex.Speaker.reading
    var quietMode by rememberSaveable { mutableStateOf(false) }
    var identityExpanded by remember { mutableStateOf(false) }
    var identityDrag by remember { mutableStateOf(0f) }
    val backlog = remember(model.history, model.reply, model.heard) {
        model.history.filterNot { earlier ->
            // The active question is rendered separately below as `heard`.
            // The phone relay also carries it in mirrored history, so leaving
            // it here makes a newly started chat show the first text twice
            // (and once more when the answer is recorded).
            (earlier.fromYou && earlier.text == model.heard) ||
                earlier.saidAgain(model.reply)
        }
    }

    // What the voice is saying, polled rather than pushed. The synthesiser
    // reports progress on its own thread, and a callback into Compose from
    // there is a threading problem for something a screen only needs a few
    // times a second. Idle costs one comparison every quarter second, and
    // nothing recomposes unless the numbers actually moved.
    var live by remember { mutableStateOf(Triple("", 0, 0)) }
    LaunchedEffect(Unit) {
        while (true) {
            val next = Triple(Speaker.liveText, Speaker.liveStart, Speaker.liveEnd)
            if (next != live) live = next
            delay(if (next.first.isEmpty()) 250L else 60L)
        }
    }
    val reading = live.first
    val chunks = remember(reading) { PlainText.sentences(reading, READ_CHUNK_CHARS) }
    val spokenNow = chunks.indexOfFirst { (from, to) -> live.second in from until to }

    // Everything the list puts *after* the reply. Worked out here so the
    // scroll target can be derived from the end of the list rather than by
    // mirroring the item structure below -- which is what went wrong before:
    // the target ignored the chat-title row, so it scrolled one item past
    // the answer and left its first line above the bezel.
    val trailing = model.suggestions.take(3).size
    val replyBase: (Int) -> Int = { total ->
        (total - trailing - maxOf(chunks.size, 1)).coerceAtLeast(0)
    }

    // Opening a session, or a new answer landing, puts the list where the
    // live exchange is. Without this the backlog is what you arrive at, and
    // the answer you were waiting for is somewhere below the bottom edge.
    //
    // Aimed past the suggestion chips at the end of the list so the reply
    // itself is what lands centred, rather than the offers underneath it.
    LaunchedEffect(model.reply, model.heard, model.history.size) {
        // Wait for the frame that composes the new items before measuring.
        // Read in the same frame the content changed, `layoutInfo` still
        // describes the list as it was, so this scrolled to where the list
        // used to end -- which is why a reply could land off screen.
        withFrameNanos { }
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(replyBase(total))
    }

    // Follow the voice down the answer. This is also what makes a long reply
    // readable: it opens on its first line and moves a sentence at a time at
    // the pace it is being read, instead of arriving already scrolled past
    // the beginning and waiting to be dragged back.
    LaunchedEffect(spokenNow, reading) {
        if (spokenNow < 0) return@LaunchedEffect
        withFrameNanos { }
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(replyBase(total) + spokenNow)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // The face itself starts a question.
            //
            // A watch is one thumb and no aim, and hunting for a chip at the
            // top of a round screen is most of the effort of asking. This
            // sits behind the list, so chips, the answer, and its long-press
            // still get first refusal on a touch and scrolling is untouched;
            // only a tap that lands on nothing in particular reaches here.
            //
            // No ripple: a grey flash across the orb reads as a glitch.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = model.usable && !quietMode,
                onClick = {
                    if (identityExpanded) {
                        identityExpanded = false
                    } else {
                        onTalk()
                    }
                },
            ),
    ) {
        OrbCanvas(
            state = model.orbState,
            // Speaking beats with the voice, a pulse per word; listening sits
            // at a steady low glow. Silence is flat, so a still orb means
            // nothing is happening rather than "the animation ended".
            level = {
                when {
                    Speaker.liveText.isNotEmpty() -> Speaker.speechLevel()
                    model.listening -> 0.35
                    else -> 0.0
                }
            },
            // Read through `revision` so a colour changed on the settings
            // page shows here without waiting for something else to move.
            hue = run { settings.revision; settings.hueOf(model.orbState) },
            modifier = Modifier.fillMaxSize(),
        )

        if (!quietMode) ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            // Leave room for the fixed chat identity bar. Without this, the
            // final sentence disappears underneath the title while scrolling.
            contentPadding = PaddingValues(top = 38.dp, bottom = 42.dp),
            // Centred while reading, top-anchored otherwise.
            //
            // Those are two different jobs. Arriving at a finished answer,
            // you want its first line at the top and the rest below it to
            // read downwards. Following a voice, you want the line being
            // spoken in the middle of the face, with what was just said above
            // it and what is coming below -- which is also the only position
            // that does not run off an edge as it advances.
            anchorType = if (reading.isNotBlank()) ScalingLazyListAnchorType.ItemCenter
            else ScalingLazyListAnchorType.ItemStart,
        ) {
            // The control sits above everything and is see-through.
            //
            // A filled chip in the middle of the list covered the orb, which
            // is the one part of this screen worth looking at. Compact and
            // transparent, at the top, it costs a strip of the animation
            // nobody was reading and is still there when you scroll up.
            item {
                CompactChip(
                    label = {
                        Text(
                            text = if (busy) "Stop" else "Tap to talk",
                            color = if (busy) MaterialTheme.colors.error
                            else MaterialTheme.colors.primary,
                        )
                    },
                    onClick = { if (busy) onStop() else onTalk() },
                    enabled = model.usable,
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.Transparent,
                        contentColor = MaterialTheme.colors.primary,
                    ),
                    border = ChipDefaults.chipBorder(),
                )
            }

            // Only when it has something to report; an empty line still
            // takes a line.
            if (model.status.isNotBlank()) {
                item {
                    Text(
                        text = engineStatus(model.engine, model.status),
                        style = MaterialTheme.typography.caption1,
                        color = if (model.usable) MaterialTheme.colors.onBackground
                        else MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable(enabled = model.usable) { onTalk() },
                    )
                }
            }

            // The conversation so far, above the exchange in progress.
            //
            // Dimmer and smaller than the live turn, because it is context
            // rather than the thing you came to read -- and present at all
            // because a session opened with only the newest answer on screen
            // is one you cannot follow. The list scrolls itself to the
            // current answer below, so this sits above the fold and is
            // reached by scrolling up, which is where a backlog belongs.
            // The live reply is rendered below in full, and the thread
            // remembers it the moment it lands -- so it was on screen twice,
            // once dim and once bold, which read as the agent repeating
            // itself. Compared by prefix because the backlog clips each turn
            // to fit a watch and the reply is not clipped.
            items(backlog) { said ->
                Said(said = said, engine = model.engine, compact = true)
            }

            // What was asked, however it was asked -- spoken, or a chip.
            // It stays up while the answer is being worked out, because
            // "Thinking..." on its own does not say what about.
            if (model.heard.isNotBlank()) {
                item {
                    // Italic, smaller, dimmer, and ruled off underneath.
                    // On a screen this size the question and the answer run
                    // together as one block of text, and a glance cannot tell
                    // which half it is reading -- so the question is set apart
                    // every way a watch has: weight, size, colour, and a line.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = model.usable) { onTalk() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        WatchMessageText(text = model.heard, mine = true, compact = true)
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp, bottom = 2.dp)
                                .width(28.dp)
                                .height(1.dp)
                                .background(
                                    MaterialTheme.colors.onSurfaceVariant
                                        .copy(alpha = 0.3f)
                                ),
                        )
                    }
                }
            }

            // While the voice is reading, the answer is shown a sentence at
            // a time with the words being said lit up.
            //
            // The text rendered here is the synthesiser's own copy, not the
            // one flattened for the eye. Those two strings differ -- see
            // Speaker.liveText -- and drawing the highlight at these offsets
            // over the other one would slide it further out of step with
            // every code span in the reply.
            if (reading.isNotBlank()) {
                items(chunks.size) { index ->
                    val (from, to) = chunks[index]
                    val body = reading.substring(from, to)
                    val here = index == spokenNow
                    Text(
                        text = if (!here) AnnotatedString(body) else buildAnnotatedString {
                            val lit = (live.second - from).coerceIn(0, body.length)
                            val out = (live.third - from).coerceIn(lit, body.length)
                            append(body.substring(0, lit))
                            withStyle(SpanStyle(color = MaterialTheme.colors.primary)) {
                                append(body.substring(lit, out))
                            }
                            append(body.substring(out))
                        },
                        style = MaterialTheme.typography.body1,
                        fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                        // Read, being read, still to come. The sentence in
                        // hand is the bright one; what is behind it stays
                        // legible because a wrist glanced at late should
                        // still be able to catch up by reading, not by
                        // scrolling.
                        color = MaterialTheme.colors.onBackground.copy(
                            alpha = if (here) 1f else 0.5f
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .padding(horizontal = 4.dp)
                            .combinedClickable(
                                onClick = { if (model.usable) onTalk() },
                                onLongClick = onRepeat,
                            ),
                    )
                }
            } else if (model.reply.isNotBlank()) {
                item {
                    Text(
                        text = PlainText.flatten(model.reply),
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onBackground,
                        textAlign = TextAlign.Center,
                        // Tap the answer to ask the next thing, hold it to
                        // hear it again. An answer read out while the wrist
                        // was down is an answer missed, and there is nowhere
                        // else on a watch to put a control for that -- so it
                        // goes on the thing itself.
                        modifier = Modifier.combinedClickable(
                            onClick = { if (model.usable) onTalk() },
                            onLongClick = onRepeat,
                        ),
                    )
                }
            }

            // One tap instead of saying it: on a watch, repeating a phrase
            // accurately enough to be recognised is most of the effort of
            // asking.
            items(model.suggestions.take(3)) { (label, detail) ->
                Chip(
                    label = { Text(text = label, maxLines = 2) },
                    onClick = { onSuggestion(detail) },
                    // Half-lit, so the orb reads through them. Three solid
                    // chips across the middle of a round screen hide most of
                    // the animation, and the animation is how you tell at a
                    // glance whether it is still working.
                    colors = ChipDefaults.chipColors(
                        backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.45f),
                        contentColor = MaterialTheme.colors.onSurface,
                    ),
                    border = ChipDefaults.chipBorder(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

        }

        // Identity is fixed while the transcript moves. It is intentionally
        // outside the ScalingLazyColumn so it never shrinks or scrolls away,
        // and the divider makes it clear that this is metadata, not a reply.
        if (!quietMode) Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    if (identityExpanded) Modifier.fillMaxHeight(0.5f)
                    else Modifier.height(24.dp)
                )
                .clickable(
                    enabled = !identityExpanded,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { identityExpanded = true },
                )
                .pointerInput(model.chatTitle, model.engine) {
                    detectDragGestures(
                        onDragStart = { identityDrag = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            identityDrag = (identityDrag + amount.y)
                                .coerceIn(-120f, 0f)
                        },
                        onDragEnd = {
                            identityExpanded = identityDrag < -24f
                            identityDrag = 0f
                        },
                        onDragCancel = { identityDrag = 0f },
                    )
                }
                .background(
                    color = Color.Black.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                )
                .padding(horizontal = 8.dp, vertical = 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (identityExpanded) 1f else 0.5f)
                    .height(2.dp)
                    .background(
                        engineColor(model.engine).copy(alpha = 0.55f),
                        RoundedCornerShape(4.dp),
                    )
            )
            Text(
                text = if (identityExpanded) engineLabel(model.engine).uppercase() else ">_",
                style = MaterialTheme.typography.caption3,
                color = if (identityExpanded) engineColor(model.engine)
                else MaterialTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 1.dp),
            )
            if (identityExpanded) {
                Text(
                    text = model.chatTitle.ifBlank { "New chat" },
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.88f),
                )
            }
        }

        // Tapping outside an open drawer dismisses it. The drawers are drawn
        // after this layer, so their own controls remain clickable.
        if (!quietMode && identityExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            identityExpanded = false
                        },
                    ),
            )
        }

        // The eye stays in the same small edge button whether the transcript
        // is visible or hidden. There is no second dropdown state to hunt for.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            quietMode = !quietMode
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 24.dp, height = 16.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(16.dp),
                        ),
                    contentAlignment = Alignment.Center,
            ) {
                val focusColor = MaterialTheme.colors.onBackground.copy(alpha = 0.9f)
                Canvas(modifier = Modifier.size(18.dp)) {
                    val arm = 5.dp.toPx()
                    val inset = 2.dp.toPx()
                    val right = size.width - inset
                    val bottom = size.height - inset
                    drawLine(focusColor, Offset(inset, inset), Offset(inset + arm, inset), 1.5.dp.toPx())
                    drawLine(focusColor, Offset(inset, inset), Offset(inset, inset + arm), 1.5.dp.toPx())
                    drawLine(focusColor, Offset(right, inset), Offset(right - arm, inset), 1.5.dp.toPx())
                    drawLine(focusColor, Offset(right, inset), Offset(right, inset + arm), 1.5.dp.toPx())
                    drawLine(focusColor, Offset(inset, bottom), Offset(inset + arm, bottom), 1.5.dp.toPx())
                    drawLine(focusColor, Offset(inset, bottom), Offset(inset, bottom - arm), 1.5.dp.toPx())
                    drawLine(focusColor, Offset(right, bottom), Offset(right - arm, bottom), 1.5.dp.toPx())
                    drawLine(focusColor, Offset(right, bottom), Offset(right, bottom - arm), 1.5.dp.toPx())
                }
                }
            }
        }
    }
}

/**
 * Which project, and which conversation inside it.
 *
 * One list showing one thing at a time. Projects used to sit under the chats,
 * which meant scrolling past every conversation to reach them -- and worse,
 * after switching, the list refreshed while the scroll position stayed deep
 * in the old one, so picking a project looked like it had done nothing at all.
 *
 * The header names the open project and switches the list to the others.
 * Choosing one puts the list back to that project's chats, at the top, which
 * is the answer to "did that work".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatsScreen(
    model: WearState,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onEnterChat: () -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    var pickingProject by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf("") }

    // Back to the top whenever the list underneath changes out from under the
    // user, so a new project's chats are what is on screen.
    LaunchedEffect(model.activeProject, pickingProject) {
        listState.scrollToItem(0)
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        item {
            Text(
                text = engineLabel(model.engine),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        item {
            Chip(
                label = {
                    Text(
                        text = model.activeProjectName.ifBlank { "Project" },
                        maxLines = 1,
                    )
                },
                secondaryLabel = {
                    Text(if (pickingProject) "pick one" else "tap to switch", maxLines = 1)
                },
                onClick = { pickingProject = !pickingProject },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (pickingProject) {
            items(model.projects) { (path, name) ->
                if (confirmingDelete == path) {
                    // The confirmation replaces the row it is about, so what
                    // is being deleted is never in doubt -- there is no room
                    // on a watch for a dialog that names it somewhere else.
                    Chip(
                        label = { Text("Remove $name?", maxLines = 2) },
                        secondaryLabel = { Text("chats and memory", maxLines = 1) },
                        onClick = {
                            onDeleteProject(path)
                            confirmingDelete = ""
                            pickingProject = false
                        },
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = MaterialTheme.colors.error,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    CompactChip(
                        label = { Text("Keep it") },
                        onClick = { confirmingDelete = "" },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Chip(
                        label = { Text(text = name, maxLines = 1) },
                        secondaryLabel = if (path == model.activeProject) {
                            { Text("open", maxLines = 1) }
                        } else null,
                        onClick = {
                            onOpenProject(path)
                            // Straight back to the chats, because choosing a
                            // project is only ever a way of choosing a chat.
                            pickingProject = false
                        },
                        colors = if (path == model.activeProject)
                            ChipDefaults.primaryChipColors()
                        else ChipDefaults.secondaryChipColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            // Hold to remove. Tapping opens, holding asks --
                            // and asking is not the same as doing.
                            .combinedClickable(
                                onClick = {
                                    onOpenProject(path)
                                    pickingProject = false
                                },
                                onLongClick = { confirmingDelete = path },
                            ),
                    )
                }
            }
            if (model.projects.isEmpty()) {
                item {
                    Text(
                        text = "No projects",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@ScalingLazyColumn
        }

        // Outlined, not filled. This used to wear the same solid accent as
        // the chat you are currently in, so the top of the list read as two
        // selected rows -- and which one you were actually in was a guess.
        // The filled accent now means one thing only: this is the open one.
        item {
            CompactChip(
                label = { Text("+  New chat") },
                onClick = onNewChat,
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.Transparent,
                    contentColor = MaterialTheme.colors.primary,
                ),
                border = ChipDefaults.chipBorder(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(model.chats) { (id, title) ->
            val open = id == model.activeChatId
            Chip(
                label = { Text(text = title, maxLines = 2) },
                // Said in words as well as in colour. Colour alone is a poor
                // signal on a small screen held at arm's length, in sunlight,
                // or by anyone who does not see this palette the way I do.
                secondaryLabel = if (open) {
                    { Text("open", maxLines = 1) }
                } else null,
                // Tapping the one you are already in is not a request to
                // reopen it -- that round trip clears the reply on screen to
                // reload a conversation nothing has changed about. It just
                // means "take me there", so it only moves the pager.
                onClick = { if (open) onEnterChat() else onOpenChat(id) },
                colors = if (open) ChipDefaults.primaryChipColors()
                else ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
        if (model.chats.isEmpty()) {
            item {
                Text(
                    text = "No chats yet",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * How it connects, and to what.
 *
 * Deliberately two controls and no more. A watch is a bad place to configure
 * anything, so what is here is only what cannot be decided for you: which
 * route, and the address if that route is Wi-Fi.
 */
/**
 * How much of a reply is one line on the wrist while it is read aloud.
 *
 * Roughly two lines at body size. Small enough that the item being spoken
 * fits on screen when centred -- a whole paragraph as one item puts the
 * highlighted word wherever it happens to fall, usually off the bottom.
 */
private const val READ_CHUNK_CHARS = 58

/** The states worth colouring, in the order they happen in a turn. */
private val ORB_STATES = listOf(
    OrbModel.State.LISTENING to "Listening",
    OrbModel.State.THINKING to "Thinking",
    OrbModel.State.WORKING to "Working",
    OrbModel.State.SPEAKING to "Speaking",
    OrbModel.State.IDLE to "Idle",
)

/**
 * One state's colour, with the colour on screen while it is chosen.
 *
 * The swatch is the point. A slider alone moved a number against a preview
 * that was somewhere else entirely -- on the talk page, in a state the orb
 * was not currently in -- so setting the thinking colour meant changing it,
 * leaving, waiting for something to think, and coming back.
 *
 * The last stop past the end of the wheel is every colour rather than one.
 */
@Composable
private fun OrbColour(
    label: String,
    state: OrbModel.State,
    settings: WearSettings,
) {
    val hue = run { settings.revision; settings.hueOf(state) }
    // Stepped by two buttons rather than a slider, and not for looks: a
    // slider inside a scrolling list eats the scroll. Dragging past one to
    // reach the row below changed the colour of the row you were dragging
    // over, which is a setting that alters itself when you look at the
    // others. Buttons cannot be triggered by a swipe.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        CompactChip(
            onClick = { settings.setHue(state, step(hue, -1)) },
            label = { Text("\u2212") },
            colors = ChipDefaults.secondaryChipColors(),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            OrbSwatch(hue = hue, modifier = Modifier.size(22.dp))
            Text(
                text = if (hue >= WearSettings.CYCLE) "$label \u00b7 all" else label,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompactChip(
            onClick = { settings.setHue(state, step(hue, 1)) },
            label = { Text("+") },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
}

/**
 * The next colour round the wheel, with "every colour" as the last stop.
 *
 * Twelve stops rather than 360: picking a hue one degree at a time on a watch
 * is a hundred taps, and no one can see the difference anyway.
 */
private fun step(hue: Float, by: Int): Float {
    val stops = 12
    val here = if (hue >= WearSettings.CYCLE) stops else (hue / 30f).toInt()
    val next = ((here + by) % (stops + 1) + (stops + 1)) % (stops + 1)
    return if (next == stops) WearSettings.CYCLE else next * 30f
}

/**
 * Whether this remembered turn is the live reply showing up a second time.
 *
 * Prefix rather than equality: the backlog clips each turn to fit a watch
 * and the live reply is not clipped, so the two are never equal even when
 * they are the same words.
 */
private fun org.hermes.duplex.WearLink.Said.saidAgain(reply: String): Boolean =
    !fromYou && text.isNotEmpty() && reply.startsWith(text)

/**
 * One turn of the conversation, attributed.
 *
 * Both sides used to arrive as centred text separated only by weight and
 * opacity, which on a round face at arm's length is not a distinction --
 * scrolling back, there was no way to tell a question from its answer.
 *
 * So the two are told apart three ways at once, because any one of them
 * fails somewhere: side, colour, and a word. Side is the fastest to read and
 * survives a glance; colour carries it when a turn is one line and there is
 * no shape to compare it against; the label is the one that still works for
 * anyone who cannot use the other two.
 */
@Composable
private fun Said(
    said: org.hermes.duplex.WearLink.Said,
    engine: String,
    compact: Boolean,
) {
    val mine = said.fromYou
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!compact) {
            Text(
                text = if (mine) "YOU" else engineLabel(engine).uppercase(),
                style = MaterialTheme.typography.caption3,
                color = if (mine) MaterialTheme.colors.primary
                else MaterialTheme.colors.secondary,
                textAlign = if (mine) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        WatchMessageText(text = said.text, mine = mine, compact = compact)
        // Match the phone's two-level command detail: open the count first,
        // then open one command to see its full command and returned output.
        if (!mine && (said.steps.isNotEmpty() || said.detail > 0)) {
            var detailsOpen by remember(said, compact) { mutableStateOf(false) }
            var openStep by remember(said, compact) { mutableStateOf(-1) }
            val stepCount = maxOf(said.detail, said.steps.size)
            if (!detailsOpen) {
                Text(
                    text = "▸  ${if (stepCount == 1) "1 command" else "$stepCount commands"}",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.secondary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp).clickable {
                        detailsOpen = true
                    },
                )
            } else {
                Text(
                    text = "▾  ${if (stepCount == 1) "1 command" else "$stepCount commands"}",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.padding(top = 2.dp).clickable {
                        detailsOpen = false
                        openStep = -1
                    },
                )
                said.steps.forEachIndexed { index, step ->
                    val expanded = openStep == index
                    val caption = buildString {
                        if (step.tool.isNotBlank()) append(step.tool)
                        val subject = step.subject.ifBlank { step.command }
                        if (subject.isNotBlank()) {
                            if (isNotEmpty()) append("  ")
                            append(subject)
                        }
                    }.ifBlank { "Command ${index + 1}" }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(
                                MaterialTheme.colors.surface.copy(alpha = 0.55f),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable {
                                openStep = if (expanded) -1 else index
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "${if (expanded) "▾" else "▸"}  $caption",
                            style = MaterialTheme.typography.caption3,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (expanded) MaterialTheme.colors.primary
                            else MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (expanded) {
                            if (step.command.isNotBlank()) {
                                Text(
                                    text = "RUN",
                                    style = MaterialTheme.typography.caption3,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colors.secondary,
                                    modifier = Modifier.padding(top = 5.dp),
                                )
                                Text(
                                    text = step.command,
                                    style = MaterialTheme.typography.caption3,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colors.primary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Text(
                                text = "OUTPUT\n${step.output.ifBlank { "No output" }}",
                                style = MaterialTheme.typography.caption3,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                if (said.steps.isEmpty()) {
                    Text(
                        text = "Command details are available on the phone.",
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

private data class WatchTextBlock(val text: String, val code: Boolean)

/** Preserve paragraphs and make fenced output readable on a small screen. */
private fun watchTextBlocks(source: String): List<WatchTextBlock> {
    val blocks = mutableListOf<WatchTextBlock>()
    val normal = StringBuilder()
    val code = StringBuilder()
    var inCode = false

    fun flushNormal() {
        normal.toString().trim().takeIf { it.isNotBlank() }?.let {
            blocks += WatchTextBlock(it, code = false)
        }
        normal.clear()
    }
    fun flushCode() {
        code.toString().trimEnd().takeIf { it.isNotBlank() }?.let {
            blocks += WatchTextBlock(it, code = true)
        }
        code.clear()
    }

    source.trim().lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) flushCode() else flushNormal()
            inCode = !inCode
        } else if (inCode) {
            code.appendLine(line)
        } else {
            normal.appendLine(line)
        }
    }
    if (inCode) flushCode() else flushNormal()
    return blocks.ifEmpty { listOf(WatchTextBlock(source.trim(), code = false)) }
}

@Composable
private fun WatchMessageText(text: String, mine: Boolean, compact: Boolean) {
    val blocks = remember(text) { watchTextBlocks(text) }
    Column(
        modifier = Modifier
            .fillMaxWidth(if (mine) 0.9f else 1f)
            .background(
                color = if (mine) MaterialTheme.colors.surface.copy(alpha = 0.42f)
                else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 8.dp, vertical = if (compact) 3.dp else 5.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        blocks.forEach { block ->
            Text(
                text = block.text,
                // The full thread is a reading log, not the live reply: keep
                // it one step smaller so many turns fit without looking like
                // one oversized block on the watch.
                style = (if (compact) MaterialTheme.typography.caption2
                else MaterialTheme.typography.caption1).copy(
                    fontStyle = if (mine && !block.code) FontStyle.Italic
                    else FontStyle.Normal,
                    fontWeight = FontWeight.Normal,
                ),
                fontFamily = if (block.code) FontFamily.Monospace else FontFamily.Default,
                color = if (mine) MaterialTheme.colors.primary.copy(alpha = 0.82f)
                else MaterialTheme.colors.onBackground.copy(
                    alpha = if (compact) 0.7f else 1f,
                ),
                textAlign = if (mine) TextAlign.End else TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .background(
                        color = if (block.code) MaterialTheme.colors.surface.copy(alpha = 0.8f)
                        else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = if (block.code) 5.dp else 0.dp),
            )
        }
    }
}

/**
 * The whole thread, for reading rather than for talking.
 *
 * It does not scroll itself. The talk screen jumps to the live exchange every
 * time a block lands, which is right there and wrong here: reading back
 * through what an agent did is impossible on a list that keeps moving.
 */
@Composable
private fun ThreadScreen(model: WearState) {
    val listState = rememberScalingLazyListState()
    // The reply in flight belongs at the end of the thread, but it is only
    // folded into `history` once it is finished -- so without this the newest
    // thing on screen is missing from the very view meant to show everything.
    val scope = rememberCoroutineScope()
    // Offered only while something is below the fold. A control that jumps
    // you to where you already are is a control you learn to ignore, and on a
    // face this small it would be covering a line of the thread to do it.
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    val thread = remember(model.history, model.reply) {
        // `reply` holds the last thing the agent said, which is not always
        // the last thing that happened: ask a question and it still holds the
        // answer before it. Appending it blindly put a stale reply *after* the
        // newer question, so the same words showed twice and out of order.
        val already = model.history.any { it.saidAgain(model.reply) }
        if (model.reply.isBlank() || already) model.history
        else model.history + org.hermes.duplex.WearLink.Said(
            fromYou = false, text = model.reply
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        anchorType = ScalingLazyListAnchorType.ItemStart,
    ) {
        item {
            Text(
                text = "${engineLabel(model.engine)} · " +
                    model.chatTitle.ifBlank { "Thread" },
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (thread.isEmpty()) {
            item {
                Text(
                    text = "Nothing said yet.",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(thread) { said ->
            Said(said = said, engine = model.engine, compact = false)
        }
    }
        if (!atEnd) {
            CompactChip(
                onClick = {
                    scope.launch {
                        val total = listState.layoutInfo.totalItemsCount
                        if (total > 0) listState.animateScrollToItem(total - 1)
                    }
                },
                label = {
                    Text(
                        text = "\u2193",
                        style = MaterialTheme.typography.button,
                        textAlign = TextAlign.Center,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    model: WearState,
    onEngine: (String) -> Unit,
    settings: WearSettings,
    onEditBridge: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        item {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }
        item {
            ToggleChip(
                checked = settings.route == WearSettings.Route.PHONE,
                onCheckedChange = { settings.choose(WearSettings.Route.PHONE) },
                label = { Text("Through phone") },
                toggleControl = {
                    androidx.wear.compose.material.RadioButton(
                        selected = settings.route == WearSettings.Route.PHONE,
                    )
                },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ToggleChip(
                checked = settings.route == WearSettings.Route.WIFI,
                onCheckedChange = { settings.choose(WearSettings.Route.WIFI) },
                label = { Text("Wi-Fi direct") },
                secondaryLabel = { Text("Works without the phone") },
                toggleControl = {
                    androidx.wear.compose.material.RadioButton(
                        selected = settings.route == WearSettings.Route.WIFI,
                    )
                },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = "Answered by",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        item {
            // Not a watch setting. It lives in the bridge's config, so the
            // phone shows the same choice and picking on either moves both --
            // they are two windows onto one session, and a watch that
            // disagreed with the phone about who was answering would be a
            // state worth no one's time to reason about.
            //
            // Built from what the bridge says it has, rather than from a
            // pair of choices written here. As a Claude-or-not switch this
            // read "off" for any other agent, and the next tap would quietly
            // move you onto Claude from something you had chosen elsewhere.
            ToggleChip(
                checked = model.engine == "hermes",
                onCheckedChange = { onEngine("hermes") },
                label = { Text("Hermes") },
                secondaryLabel = { Text("Local, fastest", maxLines = 1) },
                toggleControl = {
                    androidx.wear.compose.material.RadioButton(
                        selected = model.engine == "hermes",
                    )
                },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(model.agents) { (id, label) ->
            ToggleChip(
                checked = model.engine == id,
                onCheckedChange = { onEngine(id) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text("Live sync + approvals", maxLines = 1) },
                toggleControl = {
                    androidx.wear.compose.material.RadioButton(
                        selected = model.engine == id,
                    )
                },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ToggleChip(
                checked = settings.speakReplies,
                onCheckedChange = settings::setSpeaking,
                label = { Text("Read replies aloud") },
                secondaryLabel = {
                    Text(
                        text = if (settings.speakReplies) "Speaks each block"
                        else "Buzz only",
                        maxLines = 1,
                    )
                },
                toggleControl = { Switch(checked = settings.speakReplies) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
        item {
            Text(
                text = "Orb colours",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        items(ORB_STATES.size) { index ->
            val (state, label) = ORB_STATES[index]
            OrbColour(label = label, state = state, settings = settings)
        }
        item {
            Text(
                text = "Speech volume",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        item {
            InlineSlider(
                value = settings.volume,
                onValueChange = settings::setLoudness,
                decreaseIcon = { Text("\u2212") },
                increaseIcon = { Text("+") },
                valueRange = 0.1f..1f,
                steps = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            // A way to ask "is the voice working" that does not need an agent,
            // a turn, or a reply to land. When speech fails the whole chain is
            // suspect -- link, bridge, engine -- and this takes all of it out
            // of the question but the last link.
            Chip(
                label = { Text("Test voice", maxLines = 1) },
                secondaryLabel = { Text("Say a line now", maxLines = 1) },
                onClick = {
                    Speaker.volume = settings.volume
                    Speaker.say("Speech is working on your watch.")
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                label = { Text("Bridge address", maxLines = 1) },
                secondaryLabel = {
                    Text(
                        text = settings.bridgeUrl
                            .removePrefix("ws://")
                            .removeSuffix("/ws"),
                        maxLines = 1,
                    )
                },
                onClick = onEditBridge,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
    }
}
