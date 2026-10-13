package org.hermes.duplex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import kotlinx.coroutines.launch
import org.hermes.duplex.Approvals
import org.hermes.duplex.R
import org.hermes.duplex.Sidebar
import org.hermes.duplex.ui.theme.Palette

/** Where the overflow menu can send you. */
enum class Destination { SETTINGS, TIMING, DEBUG, USAGE, REMOTES, FILES }

/**
 * The chat screen, and the voice stage that folds down over it.
 *
 * Shaped like AgentKit's: a thread that scrolls, a composer pinned to the
 * bottom, LLM-authored suggestions docked above it, and the headset button in
 * the composer row as the way into hands-free. Typing works without the
 * microphone ever opening -- the bridge accepts a typed prompt on the same
 * socket.
 */
@Composable
fun MainScreen(session: MainSession, onNavigate: (Destination) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    // Back closes things in the order they opened: drawer, then the orb. The
    // session itself is ended by holding the headset, or from the notification.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = session.voiceExpanded && !drawer.isOpen) { session.showVoiceStage(false) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            SidebarPanel(
                session = session,
                onSettings = { onNavigate(Destination.SETTINGS) },
                onFiles = { onNavigate(Destination.FILES) },
                onClose = { scope.launch { drawer.close() } },
            )
        },
    ) {
        Box(Modifier.fillMaxSize().background(Palette.bgApp)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                TopBar(
                    session = session,
                    onMenu = {
                        keyboard?.hide()
                        scope.launch { drawer.open() }
                        session.requestUsage()
                    },
                    onNavigate = onNavigate,
                )
                StatusStrip(session)
                UsageBanner(session.usage) { onNavigate(Destination.USAGE) }
                ThreadList(session, Modifier.weight(1f))
                SuggestionStrip(session)
                Composer(session)
            }

            if (session.voiceExpanded) VoiceStage(session)
        }
    }

    DialogHost(session)
    ApprovalDialog(session)
}

// -- top bar ------------------------------------------------------------------------------------

@Composable
private fun TopBar(session: MainSession, onMenu: () -> Unit, onNavigate: (Destination) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = session.sidebarRevision
    var menu by remember { mutableStateOf(false) }
    val title = session.cliTitle()
        ?: Sidebar.activeChat?.title
        ?: stringResource(R.string.app_name)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onMenu)
                .semantics { contentDescription = "Projects and chats" },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_menu), null,
                colorFilter = ColorFilter.tint(Palette.textPrimary),
            )
        }
        Text(
            text = title,
            color = Palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Box {
            IconTap(R.drawable.ic_more, "More options", { menu = true }, tint = Palette.textPrimary, size = 24.dp, target = 48.dp)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable fun item(label: String, action: () -> Unit) = DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { menu = false; action() },
                )
                item(stringResource(R.string.action_new)) { session.newConversation() }
                item("Files") { onNavigate(Destination.FILES) }
                item(stringResource(R.string.timing)) { onNavigate(Destination.TIMING) }
                item(stringResource(R.string.usage)) { onNavigate(Destination.USAGE) }
                item(stringResource(R.string.remotes)) { onNavigate(Destination.REMOTES) }
                item(stringResource(R.string.debug)) { onNavigate(Destination.DEBUG) }
                item(stringResource(R.string.settings)) { onNavigate(Destination.SETTINGS) }
                item(stringResource(R.string.action_disconnect)) { session.disconnect() }
            }
        }
    }
}

/** The connection strip: one line, a dot in the state's colour, and the host. */
@Composable
private fun StatusStrip(session: MainSession) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(session.tone.color())
        Text(
            text = session.status,
            color = Palette.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(session.host, color = Palette.textTertiary, fontSize = 11.sp)
    }
}

// -- the thread -------------------------------------------------------------------------------------

/**
 * The messages, newest at the bottom.
 *
 * Laid out reversed so the newest row is anchored to the bottom edge: a
 * streaming reply grows upward from where the reader is looking, and the
 * list never has to chase it. Follows the conversation only while the reader
 * is at the bottom -- an agent working for ten minutes must not keep yanking
 * the view down from whatever they scrolled up to re-read.
 */
@Composable
private fun ThreadList(session: MainSession, modifier: Modifier) {
    val state = rememberLazyListState()
    val messages = session.messages
    var follow by remember { mutableStateOf(true) }

    // Only the reader's own scrolling decides whether to follow. Content
    // growing under a stationary list must not look like leaving the bottom.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = state.firstVisibleItemIndex <= 1 &&
                state.firstVisibleItemScrollOffset < 200
        }
    }
    LaunchedEffect(messages) {
        if (follow && messages.isNotEmpty()) state.scrollToItem(0)
    }
    LaunchedEffect(session.forceScroll) {
        follow = true
        if (messages.isNotEmpty()) state.scrollToItem(0)
    }

    Box(modifier.fillMaxWidth()) {
        if (messages.isEmpty()) {
            Text(
                text = stringResource(R.string.hint),
                color = Palette.textTertiary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        LazyColumn(
            state = state,
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                // A tap that is not on a private idea collapses its stepper.
                // Children see the touch first and mark it; this decides after.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        if (!session.ideaTouched) session.collapseIdeas()
                        session.ideaTouched = false
                    }
                },
        ) {
            val reversed = messages.asReversed()
            itemsIndexed(reversed, key = { index, _ -> messages.size - 1 - index }) { _, message ->
                MessageItem(message, session)
            }
        }
    }
}

// -- suggestions ------------------------------------------------------------------------------------------

/**
 * The suggestion strip.
 *
 * Nothing to show means it is hidden outright, so an empty strip never holds
 * a row of space above the composer. Set aside, it collapses to a count
 * rather than vanishing -- the offers are still there, and a control that
 * disappears when used cannot be undone.
 */
@Composable
private fun SuggestionStrip(session: MainSession) {
    val offers = session.offers
    if (offers.isEmpty()) return

    if (session.offersDismissed) {
        Text(
            text = stringResource(
                if (offers.size == 1) R.string.suggestions_restore else R.string.suggestions_restore_plural,
                offers.size,
            ),
            color = Palette.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(androidx.compose.foundation.BorderStroke(1.dp, Palette.borderDefault), RoundedCornerShape(14.dp))
                .clickable { session.restoreOffers() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Private local-LLM reflection; never submits a turn to Codex or Claude.
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Palette.ideaBackground)
                    .clickable { session.askForIdea() }
                    .semantics { contentDescription = "Ask local LLM for a private project idea" },
                contentAlignment = Alignment.Center,
            ) { Text("?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

            // Tapping sends the detail, which is the fuller phrasing.
            offers.forEach { (label, detail) ->
                Text(
                    text = label,
                    color = Palette.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.bgRaised)
                        .border(androidx.compose.foundation.BorderStroke(1.dp, Palette.borderDefault), RoundedCornerShape(18.dp))
                        .clickable { session.submit(detail) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        IconTap(R.drawable.ic_refresh, "Refresh suggestions", { session.refreshSuggestions() })
        IconTap(R.drawable.ic_close, "Set suggestions aside", { session.dismissOffers() })
    }
}

// -- composer ---------------------------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Composer(session: MainSession) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.bgPanel)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // The endpointing verdict, only while a voice session is up.
        session.badge?.let { badge ->
            Text(
                text = badge.text,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(badge.tone.color())
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // What is attached, as chat apps show it: an image is a small
        // thumbnail with an X in its corner, any other file is a name with an X.
        // Both appear the moment the file is chosen or pasted, while it is still
        // uploading, and the X works at any point -- it calls the upload back.
        if (session.attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                session.attachments.forEach { file -> AttachmentTile(file) { session.removeAttachment(file) } }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.bgRaised)
                .border(androidx.compose.foundation.BorderStroke(1.dp, Palette.borderDefault), RoundedCornerShape(20.dp))
                .padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                state = session.draftState,
                modifier = Modifier
                    .weight(1f)
                    // What the keyboard may hand over. Declaring this is what
                    // makes its "paste image" and clipboard strip work: an image
                    // it sends becomes an attachment instead of being refused.
                    .contentReceiver(object : ReceiveContentListener {
                        override fun onReceive(transferableContent: TransferableContent): TransferableContent? =
                            transferableContent.consume { item ->
                                val uri = item.uri
                                if (uri != null) session.attachFromKeyboard(uri)
                                uri != null
                            }
                    })
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.textPrimary, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                decorator = TextFieldDecorator { inner ->
                    Box {
                        if (session.draftState.text.isEmpty()) {
                            Text(stringResource(R.string.composer_hint), color = Palette.textTertiary, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
            )
            if (session.busy) {
                IconTap(R.drawable.ic_stop, "Stop", { session.stop() }, tint = Palette.statusFailed, size = 22.dp, target = 44.dp)
            }
            ToolText("Send", "Send", Palette.accent, bold = true) {
                keyboard?.hide()
                session.send()
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(R.drawable.ic_attach, "Attach files", false) { session.pickAttachments() }
            ToolIcon(R.drawable.ic_paste, "Paste from clipboard", false) { session.pasteFromClipboard() }
            ToolIcon(R.drawable.ic_recall, stringResource(R.string.recall_next), session.recallArmed) {
                session.toggleRecall()
            }
            ToolIcon(R.drawable.ic_wake, stringResource(R.string.wake_toggle), session.wakeArmed) {
                session.toggleWake()
            }
            ToolIcon(R.drawable.ic_read_aloud, stringResource(R.string.auto_read), session.readAloud) {
                session.toggleReadAloud()
            }
            // Hold to dictate into the composer, privately: the bridge
            // transcribes it and a local model tidies it, but nothing is sent.
            // An icon like its neighbours; it lights while held.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (session.rewriteHeld) Palette.accent else Color.Transparent)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            session.startRewrite()
                            tryAwaitRelease()
                            session.stopRewrite()
                        })
                    }
                    .semantics {
                        contentDescription = "Hold to dictate and rewrite with the local model"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Ico(
                    R.drawable.ic_rewrite,
                    tint = if (session.rewriteHeld) Color.White else Palette.textTertiary,
                    size = 22.dp,
                )
            }
            Spacer(Modifier.weight(1f))
            // Tap for the orb; hold to hang up. Both are advertised in the hint
            // below while a session is live -- the long press was once the only
            // way out and nothing said so.
            HeadsetButton(session)
        }
        Text(
            text = stringResource(if (session.duplex) R.string.composer_tip_live else R.string.composer_tip),
            color = Palette.textTertiary,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 6.dp, top = 2.dp),
        )
    }
}

@Composable
private fun ToolText(label: String, description: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 14.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** A composer tool, lit while its mode is on. */
@Composable
private fun ToolIcon(drawable: Int, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Palette.accentMuted else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(drawable), null,
            colorFilter = ColorFilter.tint(if (active) Palette.accent else Palette.textTertiary),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun HeadsetButton(session: MainSession) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (session.duplex) Palette.accentMuted else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { session.onHeadset() },
                    onLongPress = { session.onHeadsetLongPress() },
                )
            }
            .semantics { contentDescription = "Voice mode. Tap to start or show, hold to end." },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_headset), null,
            colorFilter = ColorFilter.tint(if (session.duplex) Palette.accent else Palette.textTertiary),
            modifier = Modifier.size(24.dp),
        )
    }
}

// -- dialogs -------------------------------------------------------------------------------------------------------

@Composable
private fun DialogHost(session: MainSession) {
    val active = session.dialog ?: return
    val close = { session.dialog = null }
    when (active) {
        is Dialog.RenameChat -> {
            var title by remember(active) { mutableStateOf(active.chat.title) }
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.rename_chat_title)) },
                text = { TextField(title, { title = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        if (title.isNotBlank()) session.renameChat(active.chat, title.trim())
                        close()
                    }) { Text(stringResource(R.string.rename)) }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is Dialog.DeleteChat -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.delete_chat_title, active.chat.title)) },
            text = { Text(stringResource(R.string.delete_chat_note)) },
            confirmButton = {
                TextButton(onClick = { session.deleteChat(active.chat); close() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        )
        is Dialog.DeleteCli -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Delete “${active.session.title}”?") },
            text = { Text("This permanently deletes the ${Sidebar.cli.label} session.") },
            confirmButton = {
                TextButton(onClick = { session.deleteCliSession(active.session); close() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        )
        Dialog.NewProject -> {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.new_project_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.new_project_note), fontSize = 13.sp)
                        TextField(
                            name, { name = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.new_project_hint)) },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (name.isNotBlank()) session.newProject(name.trim())
                        close()
                    }) { Text(stringResource(R.string.create)) }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is Dialog.DeleteProject -> AlertDialog(
            onDismissRequest = close,
            // Spelled out rather than softened: a project is every conversation
            // held in it and everything the agent learned from them. The bridge
            // moves it to a bin rather than erasing it, and the dialog says so.
            title = { Text(stringResource(R.string.delete_project_title, active.project.name)) },
            text = { Text(stringResource(R.string.delete_project_note, active.project.chats)) },
            confirmButton = {
                TextButton(onClick = { session.deleteProject(active.project); close() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        )
        is Dialog.IdeaDryRun -> {
            val payload = session.promptPayload(active.instruction)
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Dry run — nothing sent") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                "Instruction to send:\n\n${active.instruction}\n\n" +
                                    "Exact payload:\n\n${payload.toString(2)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { session.submit(active.instruction); close() }) {
                        Text("Confirm & Send")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = close) { Text("Cancel") }
                        TextButton(onClick = { session.draft = active.instruction; close() }) { Text("Edit") }
                    }
                },
            )
        }
    }
}

/**
 * Ask about the oldest thing the agent is waiting on.
 *
 * One at a time, oldest first. A reply that wants to run three commands
 * produces three questions, and three stacked dialogs is a place where people
 * tap to make the pile go away rather than to decide.
 *
 * The command itself is the body, in monospace and not truncated. It is the
 * whole of what the decision rests on: a dialog that says "Bash wants to run a
 * command" and hides the command is a rubber stamp with a confirmation step.
 * Not dismissible by tapping outside -- nobody answering means no, after three
 * minutes, and that should be a decision rather than a stray tap.
 */
@Composable
private fun ApprovalDialog(session: MainSession) {
    val request: Approvals.Request = session.approval ?: return
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false, dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.approve_title, request.tool)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column {
                        if (request.reason.isNotBlank()) {
                            Text("Reason\n${request.reason}", fontSize = 14.sp,
                                modifier = Modifier.padding(bottom = 12.dp))
                        }
                        Text(request.subject, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        if (request.cwd.isNotEmpty()) {
                            Text(
                                stringResource(R.string.approve_in, request.cwd),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Palette.textTertiary,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.approve_note),
                    fontSize = 11.sp, color = Palette.textTertiary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { session.answerApproval(request, allow = true, remember = false) }) {
                    Text(stringResource(R.string.approve_allow))
                }
                TextButton(onClick = { session.answerApproval(request, allow = true, remember = true) }) {
                    Text(stringResource(R.string.approve_always))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { session.answerApproval(request, allow = false, remember = false) }) {
                Text(stringResource(R.string.approve_deny), color = Palette.statusFailed)
            }
        },
    )
}

private fun sizeLabel(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1048576.0)
}

@Composable
private fun AttachmentTile(file: Attachment, onRemove: () -> Unit) {
    val failed = file.state == UploadState.FAILED
    val border = androidx.compose.foundation.BorderStroke(1.dp, if (failed) Palette.statusFailed else Palette.borderDefault)
    // The corner X overhangs the tile a little, as it does in a browser chat.
    Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
        val thumb = file.thumbnail
        if (thumb != null) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(border, RoundedCornerShape(12.dp)),
            ) {
                androidx.compose.foundation.Image(
                    bitmap = thumb.asImageBitmap(), contentDescription = file.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (file.state != UploadState.DONE) {
                    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        if (failed) Text("!", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        else androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White,
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .heightIn(min = 44.dp)
                    .widthIn(max = 190.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.bgRaised)
                    .border(border, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(file.name, color = Palette.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (file.state) {
                        UploadState.UPLOADING -> "uploading…"
                        UploadState.DONE -> sizeLabel(file.bytes)
                        UploadState.FAILED -> file.error
                    },
                    color = if (failed) Palette.statusFailed else Palette.textTertiary,
                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Palette.textPrimary)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "Remove ${file.name}" },
            contentAlignment = Alignment.Center,
        ) { Ico(R.drawable.ic_close, tint = Palette.bgApp, size = 14.dp) }
    }
}
