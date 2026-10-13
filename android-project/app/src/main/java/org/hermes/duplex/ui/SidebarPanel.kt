package org.hermes.duplex.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hermes.duplex.R
import org.hermes.duplex.Sidebar
import org.hermes.duplex.ui.theme.Palette
import org.json.JSONObject

/**
 * The drawer: who is answering, which project, and the conversations in it.
 *
 * The bridge owns all of it. A project is a directory there with its own
 * memory, so this draws whatever the last `projects` event said. Reading
 * [MainSession.sidebarRevision] is what subscribes it to those events.
 *
 * Which agent is answering decides what everything below the heading means:
 * a bridge project and its chats, or a CLI's working directories and its
 * sessions. Never both -- listing the other agent's threads underneath offers
 * a "new chat" for something that is not going to reply.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarPanel(
    session: MainSession,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val revision = session.sidebarRevision
    val cli = Sidebar.cli
    val chats = Sidebar.chats

    Column(
        Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Palette.bgPanel)
            .padding(top = 28.dp, start = 14.dp, end = 14.dp, bottom = 12.dp),
    ) {
        // The chevron is the only hint that the heading is also a control.
        Text(
            text = "${session.engineLabel().uppercase()}  ›",
            color = Palette.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClose(); onSettings() }
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )

        // Project selector. Holding it removes the project it is showing --
        // there is no room for a delete control beside every entry in a menu.
        var projectMenu by remember { mutableStateOf(false) }
        val projectName = if (cli.enabled) cli.activeProject?.name.orEmpty()
        else Sidebar.activeProject?.name.orEmpty()
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.bgRaised)
                    .combinedClickable(
                        onClick = { projectMenu = true },
                        onLongClick = {
                            // Only the bridge's own projects. A CLI's working
                            // directory is a real directory on the machine
                            // that this app did not create.
                            if (!cli.enabled) Sidebar.activeProject?.let {
                                session.dialog = Dialog.DeleteProject(it)
                            }
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = projectName.ifEmpty { stringResource(R.string.sidebar_project) },
                    color = Palette.textPrimary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.icon_chevron_down), color = Palette.textTertiary)
            }
            DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                if (cli.enabled) {
                    cli.projects.forEach { project ->
                        DropdownMenuItem(
                            text = { Text(project.name) },
                            onClick = { projectMenu = false; session.openCliProject(project) },
                        )
                    }
                } else {
                    Sidebar.projects.forEach { project ->
                        DropdownMenuItem(
                            text = { Text(project.name) },
                            onClick = { projectMenu = false; session.openProject(project) },
                        )
                    }
                }
            }
        }

        // Both of these make a bridge project or a bridge chat, and neither
        // means anything while a CLI is answering.
        if (!cli.enabled) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("+ " + stringResource(R.string.new_project_title), {
                    session.dialog = Dialog.NewProject
                })
                SoftButton(stringResource(R.string.sidebar_new_chat), {
                    session.newChat(); onClose()
                })
            }
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .padding(top = 8.dp),
        ) {
            if (cli.enabled) {
                item {
                    Text(
                        text = stringResource(R.string.cli_new_session, cli.label),
                        color = Palette.accent,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { session.newCliSession(); onClose() }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
                if (cli.sessions.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.cli_no_sessions),
                            color = Palette.textTertiary, fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(cli.sessions, key = { it.id }) { item ->
                    val open = item.id == cli.activeSession && cli.activeSession.isNotEmpty()
                    ChatRow(
                        title = item.title,
                        open = open,
                        detail = if (open) stringResource(R.string.cli_open) else ago(item.updatedAt),
                        onClick = { session.openCliSession(item); onClose() },
                        // A CLI's sessions cannot be renamed -- those are files
                        // that agent owns -- so only delete is offered.
                        onRename = null,
                        onDelete = { session.dialog = Dialog.DeleteCli(item) },
                    )
                }
            } else {
                if (chats.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.sidebar_no_chats),
                            color = Palette.textTertiary, fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(chats, key = { it.id }) { chat ->
                    // A bridge chat is only "the one you are in" while no CLI
                    // session is, and the bridge says so by clearing chat_id.
                    val open = chat.id == Sidebar.activeChatId && Sidebar.activeChatId.isNotEmpty()
                    ChatRow(
                        title = chat.title,
                        open = open,
                        detail = null,
                        onClick = { session.openChat(chat); onClose() },
                        onRename = { session.dialog = Dialog.RenameChat(chat) },
                        onDelete = { session.dialog = Dialog.DeleteChat(chat) },
                    )
                }
            }
        }

        UsageCard(session)

        // The footer says what the answering agent can actually see.
        val path = if (cli.enabled) cli.activeProject?.path.orEmpty() else Sidebar.activePath
        if (path.isNotEmpty()) {
            Text(
                text = path,
                color = Palette.textTertiary,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ChatRow(
    title: String,
    open: Boolean,
    detail: String?,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (open) Palette.accentMuted else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                text = title,
                color = if (open) Palette.accent else Palette.textPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The date, not the title, is what tells two similar sessions apart.
            if (!detail.isNullOrEmpty()) {
                Text(detail, color = Palette.textTertiary, fontSize = 11.sp)
            }
        }
        if (onRename != null) {
            Text(
                "✎", color = Palette.textTertiary, fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onRename).padding(8.dp),
            )
        }
        Text(
            "✕", color = Palette.textTertiary, fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onDelete).padding(8.dp),
        )
    }
}

/** "3 hours ago", from the seconds-since-epoch the bridge sends. */
private fun ago(seconds: Double): String {
    if (seconds <= 0.0) return ""
    return DateUtils.getRelativeTimeSpanString(
        (seconds * 1000).toLong(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

// -- usage ----------------------------------------------------------------------------------------

/** The quota card at the foot of the drawer. Tap to refresh. */
@Composable
private fun UsageCard(session: MainSession) {
    val event = session.usage ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.bgRaised)
            .clickable { session.requestUsage() }
            .padding(12.dp),
    ) {
        Text(
            "${session.engineLabel()} usage",
            color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        QuotaLine(MainSession.findWindow(event, short = true), "5 hour")
        QuotaLine(MainSession.findWindow(event, short = false), "Weekly")
        if (session.usageNote.isNotBlank()) {
            Text(
                session.usageNote, color = Palette.textTertiary, fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun QuotaLine(item: JSONObject?, fallback: String) {
    if (item == null) return
    if (item.optBoolean("unavailable")) {
        Text(
            "${item.optString("label").ifBlank { fallback }}: unavailable",
            color = Palette.textTertiary, fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        return
    }
    val used = item.optDouble("used", 0.0).coerceIn(0.0, 100.0)
    val reset = item.optLong("resets_at", 0L)
    val resetText = if (reset > 0) " · resets ${MainSession.formatTime(reset)}" else ""
    Text(
        "${item.optString("label").ifBlank { fallback }}: ${used.toInt()}% used · ${(100 - used).toInt()}% left$resetText",
        color = Palette.textSecondary, fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
    QuotaBar(used)
}

@Composable
fun QuotaBar(used: Double, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { (used / 100.0).toFloat().coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = if (used >= 80) Palette.statusWarning else Palette.accent,
        trackColor = Palette.borderDefault,
    )
}

/** The always-visible 5H / W banner at the top of the chat. */
@Composable
fun UsageBanner(usage: JSONObject?, onClick: () -> Unit) {
    val windows = usage?.optJSONArray("windows")
    if (usage == null || (windows?.length() ?: 0) == 0) return
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BannerQuota("5H", MainSession.findWindow(usage, true), Modifier.weight(1f))
        BannerQuota("W", MainSession.findWindow(usage, false), Modifier.weight(1f))
    }
}

@Composable
private fun BannerQuota(label: String, item: JSONObject?, modifier: Modifier) {
    if (item == null) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Palette.textTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (item.optBoolean("unavailable")) {
            Text("—", color = Palette.textTertiary, fontSize = 11.sp)
        } else {
            val used = item.optDouble("used", 0.0).coerceIn(0.0, 100.0)
            Box(Modifier.weight(1f)) { QuotaBar(used, Modifier.padding(top = 0.dp)) }
            Text(
                "${used.toInt()}% · ${(100 - used).toInt()}%",
                color = Palette.textSecondary, fontSize = 10.sp,
            )
        }
    }
}
