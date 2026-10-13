package org.hermes.duplex.wear

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.hermes.duplex.WearLink
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The watch talking to the bridge itself, with no phone in between.
 *
 * The same WebSocket the phone uses, and the same JSON, minus the half that
 * carries audio: the watch sends a typed prompt and reads the reply, exactly
 * as the phone's composer does. There is no microphone streaming here and
 * there is not going to be -- the watch listens with its own recogniser and
 * sends text.
 *
 * This only works where the watch can route to the bridge. Today the bridge
 * binds to a tailnet address, so that means a watch on the tailnet or a
 * bridge also bound to the LAN. When it cannot, saying so is the whole job:
 * a silent failure here looks identical to a slow model.
 */
class DirectLink {

    data class State(
        val connected: Boolean = false,
        val reply: String = "",
        /** The tool the followed agent has in hand, if any. */
        val doing: String = "",
        val turn: String = WearLink.Turn.IDLE,
        val chatTitle: String = "",
        val suggestions: List<Pair<String, String>> = emptyList(),
        val engine: String = "hermes",
        val claudeAvailable: Boolean = false,
        val approvalId: String = "",
        val approvalTool: String = "",
        val approvalSubject: String = "",
        val agents: List<Pair<String, String>> = emptyList(),
        /** The turns already in this conversation, oldest first. */
        val history: List<WearLink.Said> = emptyList(),
    )

    var onState: (State) -> Unit = {}
    var onChats: (List<Pair<String, String>>) -> Unit = {}
    var onActiveChat: (String) -> Unit = {}
    var onProjects: (List<Pair<String, String>>, String) -> Unit = { _, _ -> }

    /** Why the socket would not open, in words the wearer can act on. */
    var onTrouble: (String) -> Unit = {}

    private var socket: WebSocket? = null
    private var current = State()
    private var target = ""
    private var activeProjectKey = ""
    private var activeSessionId = ""

    /**
     * The open conversation, keyed by transcript turn id.
     *
     * Kept here and not in [WearLink.Said], which carries role and words and
     * nothing else on purpose. Merging deltas needs identity, and identity is
     * a fact about the transcript rather than about what a wrist displays --
     * so it lives at the seam that does the merging.
     *
     * A LinkedHashMap because the tail turn is rewritten repeatedly as its
     * reply grows: re-putting an existing key must leave it where it was, or
     * the answer being written jumps to the bottom of the thread on every
     * poll.
     */
    private val followed = LinkedHashMap<String, WearLink.Said>()

    /**
     * Which attempt is the live one.
     *
     * A watch's Wi-Fi sleeps with the screen, so a socket dies and is retried
     * often. Every retry is stamped, and a callback from an older attempt --
     * one whose failure arrives after the wearer has already switched routes
     * or addresses -- finds its stamp stale and does nothing.
     */
    private var attempt = 0

    @Volatile
    private var wanted = false

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // a live socket never idles out
        .pingInterval(30, TimeUnit.SECONDS)      // a watch's Wi-Fi sleeps deeply
        .build()

    fun connect(url: String) {
        if (wanted) disconnect()
        wanted = true
        target = url
        val mine = ++attempt
        socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    publish(current.copy(connected = true))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                    read(event)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, r: Response?) {
                    Log.w(TAG, "socket failed: ${t.message}")
                    publish(current.copy(connected = false, turn = WearLink.Turn.IDLE))
                    // The reason goes on the screen, not only into logcat.
                    // Every direct-route failure looks the same from the
                    // wrist -- nothing happens -- and the wearer has no way
                    // to tell a wrong address from a sleeping bridge from a
                    // policy that refused the socket before it was opened.
                    main.post { if (mine == attempt) onTrouble(explain(t)) }
                    retry(mine)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    publish(current.copy(connected = false, turn = WearLink.Turn.IDLE))
                    retry(mine)
                }
            },
        )
    }

    /**
     * Come back after a drop, as long as this route is still the chosen one.
     *
     * Without it a socket lost to a sleeping radio stayed lost, and the only
     * way back was to open settings and re-pick the route -- which is why
     * Wi-Fi felt like it worked once and then stopped.
     */
    private fun retry(mine: Int) {
        if (!wanted || mine != attempt) return
        main.postDelayed({
            if (wanted && mine == attempt) connect(target)
        }, RETRY_MS)
    }

    private fun explain(t: Throwable): String {
        val message = t.message.orEmpty()
        return when {
            message.contains("CLEARTEXT") -> "Blocked: plain ws:// not permitted"
            message.contains("ECONNREFUSED", true) ||
                message.contains("refused", true) -> "Bridge not answering there"
            message.contains("EHOSTUNREACH", true) ||
                message.contains("ENETUNREACH", true) -> "No route to that address"
            message.contains("timeout", true) -> "Timed out reaching the bridge"
            message.isBlank() -> "Could not reach the bridge"
            else -> message.take(60)
        }
    }

    fun disconnect() {
        wanted = false
        attempt++
        runCatching { socket?.close(1000, "bye") }
        socket = null
        publish(State())
    }

    fun prompt(text: String) = send(
        JSONObject().put("type", "prompt").put("text", text)
            .put("engine", current.engine)
            .put("key", activeProjectKey)
            .put("id", activeSessionId)
    )

    fun stop() = send(JSONObject().put("type", "stop"))

    fun newChat() = send(
        JSONObject().put("type", "new_chat")
            .put("engine", current.engine)
            .put("key", activeProjectKey)
    )

    fun openChat(id: String) =
        send(JSONObject().put("type", "open_chat").put("id", id)
            .put("engine", current.engine)
            .put("key", activeProjectKey))

    fun openProject(path: String) =
        send(JSONObject().put("type", "open_project").put("path", path)
            .put("engine", current.engine))

    fun deleteProject(path: String) =
        send(JSONObject().put("type", "delete_project").put("path", path))

    /**
     * Answer a question the agent is blocked on.
     *
     * "Allow this run" is deliberately not offered here. On a wrist the whole
     * of the decision is one line of text read at arm's length, and a blanket
     * approval is not something to grant from that much information.
     */
    fun approve(id: String, allow: Boolean) = send(
        JSONObject().put("type", "approval_reply").put("id", id)
            .put("allow", allow)
    )

    /** Choose which agent answers: "hermes", or a CLI's id. */
    fun setEngine(engine: String) =
        send(JSONObject().put("type", "config").put("engine", engine))

    private fun send(payload: JSONObject) {
        socket?.send(payload.toString())
    }

    private fun read(event: JSONObject) {
        when (event.optString("type")) {
            "transcript", "waiting_on_model" ->
                publish(current.copy(turn = WearLink.Turn.THINKING))
            "state" -> publish(current.copy(turn = event.optString("value")))
            "reply" -> publish(
                current.copy(reply = event.optString("text"), turn = WearLink.Turn.IDLE)
            )
            "interrupted", "error" -> publish(current.copy(turn = WearLink.Turn.IDLE))
            // The one event that says, definitively, whether the agent is
            // working. A followed CLI session sends no `transcript` and no
            // `waiting_on_model` -- those belong to a turn this bridge is
            // driving -- so without this the watch never saw THINKING at all
            // and went straight from idle to speaking.
            "working" -> publish(
                current.copy(
                    turn = if (event.optBoolean("active")) WearLink.Turn.WORKING
                    else WearLink.Turn.IDLE,
                    doing = event.optString("doing"),
                )
            )
            "hello", "config" -> publish(
                current.copy(
                    engine = event.optString("engine", current.engine),
                    agents = event.optJSONArray("agents")?.let { list ->
                        buildList {
                            for (index in 0 until list.length()) {
                                val agent = list.optJSONObject(index) ?: continue
                                if (!agent.optBoolean("available")) continue
                                add(agent.optString("id") to agent.optString("label"))
                            }
                        }
                    } ?: current.agents,
                    claudeAvailable = event.optBoolean(
                        "claude_available", current.claudeAvailable
                    ),
                )
            )
            "suggestions" -> {
                val items = event.optJSONArray("items")
                val offers = buildList {
                    for (index in 0 until (items?.length() ?: 0)) {
                        val item = items?.optJSONObject(index) ?: continue
                        val label = item.optString("label")
                        if (label.isNotEmpty()) {
                            add(label to item.optString("detail").ifEmpty { label })
                        }
                    }
                }
                publish(current.copy(suggestions = offers))
            }
            "approval_request" -> publish(
                current.copy(
                    approvalId = event.optString("id"),
                    approvalTool = event.optString("tool"),
                    approvalSubject = event.optString("subject"),
                )
            )
            "approval_closed" -> if (event.optString("id") == current.approvalId) {
                publish(current.copy(approvalId = "", approvalTool = "",
                                     approvalSubject = ""))
            }
            "chat_opened" -> {
                // A different session is a different thread: what the last
                // one had merged says nothing about this one.
                activeSessionId = event.optString("id")
                followed.clear()
                absorb(event)
                publish(
                    current.copy(
                        chatTitle = event.optString("title"),
                        reply = "",
                        // The bridge sends the transcript with this event and
                        // it was being dropped on the floor, so opening a
                        // past session showed a blank face. You cannot follow
                        // an agent you cannot read.
                        history = historyNow(),
                    )
                )
            }
            // The session is being written by a CLI nobody here is driving,
            // and the bridge is tailing the file. Without this the watch
            // showed the transcript as it stood the moment it was opened and
            // never moved again -- which is not watching an agent work, it is
            // reading a photograph of it.
            "chat_delta" -> {
                absorb(event)
                // The newest block the agent wrote becomes `reply`, not just
                // another row of `history`.
                //
                // This is why a followed session was silent on the Wi-Fi
                // route while showing every word: the face renders `history`,
                // but speech is driven off `reply`, and this branch only ever
                // touched the first. WearState.apply saw reply unchanged and
                // empty on every delta, decided nothing new had been said,
                // and returned "" -- so Speaker.say was never called at all.
                // The phone route never had the bug, which is what made it
                // look like a synthesiser fault rather than a missing field.
                //
                // SPEAKING rather than IDLE because a delta is a turn still
                // being written: announce() buzzes only on IDLE, and a wrist
                // buzzing once per block for a ten-minute run is a fault.
                val said = newestAssistant(event)
                publish(
                    if (said.isEmpty()) current.copy(history = historyNow())
                    else current.copy(
                        history = historyNow(),
                        reply = said,
                        turn = WearLink.Turn.SPEAKING,
                    )
                )
            }
            "projects" -> {
                // Whichever engine is answering owns this list. When a
                // command-line agent is, its sessions *are* the chats and its
                // working directories are the projects -- the watch shows one
                // of each and the bridge routes open/new to match, so nothing
                // here has to know which kind it is holding.
                val agent = event.optJSONObject("agent")
                    ?.takeIf { it.optBoolean("enabled") }
                // Direct Wi-Fi watches do not receive the phone's mirrored
                // state. The projects payload is still authoritative: its
                // agent section is exactly what is answering this session.
                // Without copying this id, the watch stayed on its default
                // Hermes label while the chat list was already Codex.
                current = current.copy(
                    engine = agent?.optString("id")?.ifBlank { "hermes" }
                        ?: "hermes"
                )
                val chats = agent?.optJSONArray("sessions")
                    ?: event.optJSONArray("chats")
                val list = buildList {
                    for (index in 0 until (chats?.length() ?: 0)) {
                        val chat = chats?.optJSONObject(index) ?: continue
                        add(chat.optString("id") to chat.optString("title"))
                    }
                }
                val items = agent?.optJSONArray("projects")
                    ?: event.optJSONArray("items")
                val projects = buildList {
                    for (index in 0 until (items?.length() ?: 0)) {
                        val project = items?.optJSONObject(index) ?: continue
                        add(
                            (if (agent != null) project.optString("key")
                             else project.optString("path"))
                                to project.optString("name")
                        )
                    }
                }
                val active = agent?.optString("active") ?: event.optString("active")
                val activeChat = agent?.optString("session_id")
                    ?: event.optString("chat_id")
                activeProjectKey = active
                main.post {
                    onChats(list)
                    onProjects(projects, active)
                    onActiveChat(activeChat)
                }
            }
        }
    }

    /**
     * Fold an event's turns into the open conversation.
     *
     * Used by both the whole transcript and a delta, because they are the
     * same shape and the same rule: a turn replaces the one with its id, or
     * joins the end if it is new.
     */
    private fun absorb(event: JSONObject) {
        val messages = event.optJSONArray("messages") ?: return
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val text = message.optString("content").trim()
            if (text.isEmpty()) continue
            val role = message.optString("role")
            if (role != "user" && role != "assistant") continue
            // Turns written before ids were recorded would all collide on the
            // empty string, leaving one turn where there were several.
            val id = message.optString("id").ifEmpty { "@$index" }
            val steps = message.optJSONArray("steps")
            followed[id] = WearLink.Said(
                fromYou = role == "user",
                text = text.take(WearLink.HISTORY_CHARS),
                steps = (0 until (steps?.length() ?: 0)).mapNotNull { at ->
                    val step = steps?.optJSONObject(at) ?: return@mapNotNull null
                    val tool = step.optString("tool")
                    val subject = step.optString("subject")
                    val command = step.optString("command")
                    val output = step.optString("output")
                    if (tool.isBlank() && subject.isBlank() && command.isBlank()) null
                    else WearLink.Step(tool, subject, command, output)
                }.take(WearLink.STEP_LINES),
                detail = message.optInt("detail", steps?.length() ?: 0),
            )
        }
        // A session runs for hours; a wrist reads the end of it. Trimming
        // here rather than only on the way out keeps the map from growing
        // for the whole life of the connection.
        while (followed.size > WearLink.HISTORY_TURNS) {
            followed.remove(followed.keys.first())
        }
    }

    /** The last turns of the open conversation, oldest first. */
    private fun historyNow(): List<WearLink.Said> = followed.values.toList()

    /**
     * The last thing the agent said in this delta, for reading out.
     *
     * Taken from the event rather than from `followed`, and untrimmed: that
     * map holds display rows clipped to `HISTORY_CHARS`, and speaking a
     * sentence that stops mid-word because it did not fit on a watch face is
     * worse than not speaking it.
     */
    private fun newestAssistant(event: JSONObject): String {
        val messages = event.optJSONArray("messages") ?: return ""
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "assistant") continue
            val text = message.optString("content").trim()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun publish(next: State) {
        current = next
        main.post { onState(next) }
    }

    private companion object {
        const val TAG = "DirectLink"

        /** Long enough not to spin a radio, short enough to feel automatic. */
        const val RETRY_MS = 4_000L
    }
}
