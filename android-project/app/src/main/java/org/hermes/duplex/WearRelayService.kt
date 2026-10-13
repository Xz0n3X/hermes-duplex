package org.hermes.duplex

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * The phone's half of the watch link.
 *
 * The watch cannot reach the bridge: it lives on a tailnet the watch is not
 * on, and putting it on one is not a thing you do to a watch. So the phone
 * relays -- it already holds the socket, already knows which chat is open,
 * and is already awake for the microphone.
 *
 * Actions arrive as messages and are acted on once. State goes back through
 * the DataClient, which replays the current value to a watch that connects
 * later, so a reply that arrived while the wrist was down is still there
 * when it comes up.
 *
 * Started by the system when a message arrives, which means this can run
 * with no Activity and, sometimes, with no socket. `DuplexService.start`
 * establishes one if it is missing rather than dropping what was asked.
 */
class WearRelayService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val payload = String(event.data)
        EventLog.add("watch -> ${event.path} ${payload.take(40)}")

        when (event.path) {
            WearLink.PATH_PROMPT -> withClient { client ->
                if (payload.isNotBlank()) {
                    val prompt = JSONObject().put("type", "prompt").put("text", payload)
                    if (Sidebar.cli.enabled) {
                        prompt.put("engine", Sidebar.cli.id)
                        prompt.put("key", Sidebar.cli.activeKey)
                        prompt.put("id", Sidebar.cli.activeSession)
                    }
                    client.sendCommand(prompt)
                }
            }
            WearLink.PATH_STOP -> withClient { it.sendCommand(JSONObject().put("type", "stop")) }
            WearLink.PATH_NEW_CHAT -> withClient {
                val command = JSONObject().put("type", "new_chat")
                if (Sidebar.cli.enabled) {
                    command.put("engine", Sidebar.cli.id)
                    command.put("key", Sidebar.cli.activeKey)
                }
                it.sendCommand(command)
            }
            WearLink.PATH_OPEN_CHAT -> withClient {
                it.sendCommand(JSONObject().put("type", "open_chat").put("id", payload)
                    .put("engine", Sidebar.cli.id)
                    .put("key", Sidebar.cli.activeKey))
            }
            WearLink.PATH_OPEN_PROJECT -> withClient {
                it.sendCommand(JSONObject().put("type", "open_project").put("path", payload)
                    .put("engine", Sidebar.cli.id))
            }
            WearLink.PATH_DELETE_PROJECT -> withClient {
                it.sendCommand(JSONObject().put("type", "delete_project").put("path", payload))
            }
            WearLink.PATH_ENGINE -> withClient {
                it.sendCommand(JSONObject().put("type", "config").put("engine", payload))
            }
            WearLink.PATH_APPROVE -> {
                val (id, verdict) = payload.split("|", limit = 2)
                    .let { it.getOrElse(0) { "" } to it.getOrElse(1) { "deny" } }
                // Dropped from the phone's own queue as well, so a prompt
                // answered on the wrist does not reappear here the moment
                // the screen comes on.
                Approvals.resolve(id)
                if (Approvals.oldest() == null) DuplexService.clearApproval(this)
                withClient {
                    it.sendCommand(JSONObject()
                        .put("type", "approval_reply")
                        .put("id", id)
                        .put("allow", verdict == "allow"))
                }
            }
        }
    }

    /**
     * Run [action] against a live socket, opening one if need be.
     *
     * A watch prompt is the same intent as tapping Send: it should establish
     * the connection it needs rather than failing because nobody happened to
     * have the app open.
     */
    private fun withClient(action: (DuplexClient) -> Unit) {
        val live = DuplexClient.active
        if (live != null) {
            action(live)
            return
        }
        DuplexService.start(this, SettingsActivity.serverUrl(this))
        // The client is created inside the service a moment later.
        android.os.Handler(mainLooper).postDelayed({
            DuplexClient.active?.let(action)
                ?: EventLog.add("watch command dropped: no socket")
        }, 600)
    }

    companion object {

        /**
         * Publish what the watch should be showing.
         *
         * Called from the phone's own event handling, so the watch sees the
         * same turn the phone does. The stamp matters: DataClient does not
         * notify when an item is rewritten with identical bytes, so asking
         * the same question twice would leave the watch on the first answer
         * with nothing to say the second had arrived.
         */
        fun publishState(
            context: android.content.Context,
            reply: String,
            turn: String,
            chatTitle: String,
            suggestions: List<Pair<String, String>>,
            connected: Boolean,
            engine: String = "hermes",
            claudeAvailable: Boolean = false,
            approval: Approvals.Request? = null,
            agents: List<Pair<String, String>> = emptyList(),
            history: List<WearLink.Said> = emptyList(),
        ) {
            val request = PutDataMapRequest.create(WearLink.PATH_STATE).apply {
                dataMap.putString(WearLink.KEY_REPLY, reply)
                dataMap.putString(WearLink.KEY_TURN_STATE, turn)
                dataMap.putString(WearLink.KEY_CHAT_TITLE, chatTitle)
                dataMap.putString(
                    WearLink.KEY_SUGGESTIONS, WearLink.packPairs(suggestions)
                )
                dataMap.putBoolean(WearLink.KEY_CONNECTED, connected)
                dataMap.putString(WearLink.KEY_ENGINE, engine)
                dataMap.putBoolean(WearLink.KEY_CLAUDE_AVAILABLE, claudeAvailable)
                dataMap.putString(WearLink.KEY_AGENTS, WearLink.packPairs(agents))
                // The conversation so far. Without it a session opened on the
                // wrist showed a blank face, which is unusable for the thing
                // the watch is for: watching an agent work.
                dataMap.putString(WearLink.KEY_HISTORY, WearLink.packSaid(history))
                // The wrist is often the thing being looked at while the
                // phone is in a pocket, and the agent is blocked either way.
                dataMap.putString(WearLink.KEY_APPROVAL_ID, approval?.id.orEmpty())
                dataMap.putString(WearLink.KEY_APPROVAL_TOOL, approval?.tool.orEmpty())
                dataMap.putString(
                    WearLink.KEY_APPROVAL_SUBJECT, approval?.subject.orEmpty()
                )
                dataMap.putLong(WearLink.KEY_STAMP, System.currentTimeMillis())
            }
            put(context, request)
        }

        /** The chats the watch can switch between. */
        fun publishChats(
            context: android.content.Context,
            chats: List<Pair<String, String>>,
            projects: List<Pair<String, String>> = emptyList(),
            activeProject: String = "",
        ) {
            val request = PutDataMapRequest.create(WearLink.PATH_CHATS).apply {
                dataMap.putString(WearLink.KEY_CHAT_LIST, WearLink.packPairs(chats))
                dataMap.putString(
                    WearLink.KEY_PROJECT_LIST, WearLink.packPairs(projects)
                )
                dataMap.putString(WearLink.KEY_ACTIVE_PROJECT, activeProject)
                dataMap.putLong(WearLink.KEY_STAMP, System.currentTimeMillis())
            }
            put(context, request)
        }

        private fun put(context: android.content.Context, request: PutDataMapRequest) {
            runCatching {
                Wearable.getDataClient(context)
                    .putDataItem(request.asPutDataRequest().setUrgent())
            }.onFailure { EventLog.add("watch publish failed: ${it.message}") }
        }
    }
}
