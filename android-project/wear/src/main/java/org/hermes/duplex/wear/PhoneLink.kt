package org.hermes.duplex.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.hermes.duplex.WearLink

/**
 * The watch's half of the link to the phone.
 *
 * Everything the watch knows comes from here, and everything it asks for
 * goes out through here. Two clients, for two different jobs:
 *
 * - the DataClient carries state, and replays the current value on connect,
 *   so a watch that was asleep through a reply still shows it on waking;
 *   nothing here has to be re-requested;
 * - the MessageClient carries actions, which must happen once and must not
 *   be replayed by that same catch-up.
 *
 * There is no fallback if the phone is not there. The bridge lives on a
 * tailnet the watch is not on, so the phone is not an optimisation, it is
 * the only route -- and saying so plainly on screen is better than a spinner
 * that never resolves.
 */
class PhoneLink(private val context: Context) {

    data class State(
        val connected: Boolean = false,
        val reply: String = "",
        val turn: String = WearLink.Turn.IDLE,
        val chatTitle: String = "",
        val suggestions: List<Pair<String, String>> = emptyList(),
        val engine: String = "hermes",
        val claudeAvailable: Boolean = false,
        /** Empty unless the agent is blocked waiting to be allowed. */
        val approvalId: String = "",
        val approvalTool: String = "",
        val approvalSubject: String = "",
        /** Which command-line agents the bridge has, id to label. */
        val agents: List<Pair<String, String>> = emptyList(),
        /** The turns already in this conversation, oldest first. */
        val history: List<WearLink.Said> = emptyList(),
    )

    /** Called on the main thread whenever the phone publishes something new. */
    var onState: (State) -> Unit = {}
    var onChats: (List<Pair<String, String>>) -> Unit = {}
    var onProjects: (List<Pair<String, String>>, String) -> Unit = { _, _ -> }

    /** Whether a phone running this app is currently reachable. */
    var onReachable: (Boolean) -> Unit = {}

    /**
     * Callbacks land on the main thread.
     *
     * Play services delivers these on a worker, and everything downstream is
     * Compose state and the speech engine -- neither of which is safe to
     * touch from off the main thread. It mostly appears to work, which is the
     * worst way for it to be wrong.
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onMain(action: () -> Unit) = main.post(action)

    private val data: DataClient by lazy { Wearable.getDataClient(context) }
    private val messages: MessageClient by lazy { Wearable.getMessageClient(context) }

    private val listener = DataClient.OnDataChangedListener { events ->
        readAll(events)
    }

    fun start() {
        data.addListener(listener)
        // Ask for what is already there. A watch that starts after the phone
        // has been talking would otherwise sit empty until the next reply.
        data.dataItems.addOnSuccessListener { buffer ->
            buffer.forEach { item ->
                when (item.uri.path) {
                    WearLink.PATH_STATE -> readState(DataMapItem.fromDataItem(item))
                    WearLink.PATH_CHATS -> readChats(DataMapItem.fromDataItem(item))
                }
            }
            buffer.release()
        }
    }

    fun stop() {
        data.removeListener(listener)
    }

    private fun readAll(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = DataMapItem.fromDataItem(event.dataItem)
            when (event.dataItem.uri.path) {
                WearLink.PATH_STATE -> readState(item)
                WearLink.PATH_CHATS -> readChats(item)
            }
        }
    }

    private fun readState(item: DataMapItem) {
        val map = item.dataMap
        val next = State(
                connected = map.getBoolean(WearLink.KEY_CONNECTED, false),
                reply = map.getString(WearLink.KEY_REPLY) ?: "",
                turn = map.getString(WearLink.KEY_TURN_STATE) ?: WearLink.Turn.IDLE,
                chatTitle = map.getString(WearLink.KEY_CHAT_TITLE) ?: "",
                suggestions = WearLink.unpackPairs(map.getString(WearLink.KEY_SUGGESTIONS)),
                engine = map.getString(WearLink.KEY_ENGINE) ?: "hermes",
                claudeAvailable = map.getBoolean(WearLink.KEY_CLAUDE_AVAILABLE, false),
                approvalId = map.getString(WearLink.KEY_APPROVAL_ID) ?: "",
                approvalTool = map.getString(WearLink.KEY_APPROVAL_TOOL) ?: "",
                approvalSubject = map.getString(WearLink.KEY_APPROVAL_SUBJECT) ?: "",
                agents = WearLink.unpackPairs(map.getString(WearLink.KEY_AGENTS) ?: ""),
                history = WearLink.unpackSaid(map.getString(WearLink.KEY_HISTORY)),
        )
        onMain { onState(next) }
    }

    private fun readChats(item: DataMapItem) {
        val chats = WearLink.unpackPairs(item.dataMap.getString(WearLink.KEY_CHAT_LIST))
        val projects = WearLink.unpackPairs(
            item.dataMap.getString(WearLink.KEY_PROJECT_LIST)
        )
        val active = item.dataMap.getString(WearLink.KEY_ACTIVE_PROJECT) ?: ""
        onMain {
            onChats(chats)
            onProjects(projects, active)
        }
    }

    /**
     * Send an action to whichever nodes are listening.
     *
     * Sent to every connected node rather than a remembered one: there is
     * normally exactly one phone, and remembering which it was is how a
     * watch ends up talking to a phone that has since been replaced.
     */
    suspend fun send(path: String, payload: String = "") {
        val nodes = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }.getOrElse {
            Log.w(TAG, "no nodes: ${it.message}")
            onMain { onReachable(false) }
            return
        }
        onMain { onReachable(nodes.isNotEmpty()) }
        for (node in nodes) {
            runCatching {
                messages.sendMessage(node.id, path, payload.toByteArray()).await()
            }.onFailure { Log.w(TAG, "send $path to ${node.displayName}: ${it.message}") }
        }
    }

    suspend fun refreshReachability() {
        val nodes = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }.getOrNull()
        onMain { onReachable(!nodes.isNullOrEmpty()) }
    }

    private companion object {
        const val TAG = "PhoneLink"
    }
}
