package org.hermes.duplex

import org.json.JSONObject

/**
 * Questions the agent is waiting on an answer to.
 *
 * Held here rather than in the activity for the reason [ConversationStore] is:
 * the request arrives on the socket, which the service owns, and the screen
 * that has to show it may be closed at the time. A question that arrived while
 * the phone was in a pocket must still be there when it comes out — the CLI is
 * blocked on it either way, and silently losing it means a turn that fails
 * three minutes later for no visible reason.
 *
 * Answers are not stored. The bridge is the authority on which requests are
 * still open, and it drops one the moment either device answers it, so a
 * queue kept here could only ever disagree with it.
 */
object Approvals {

    data class Request(
        val id: String,
        /** `Bash`, `Write`, `Edit`… */
        val tool: String,
        /** Optional explanation; never inferred from the command subject. */
        val reason: String,
        /** The command or path itself — the line the decision rests on. */
        val subject: String,
        /** The whole call, as JSON, for when the summary is not enough. */
        val detail: String,
        val cwd: String,
        val at: Long = System.currentTimeMillis(),
    )

    private val waiting = ArrayDeque<Request>()
    private val spoken = mutableSetOf<String>()

    /** Bumped on every change, so a re-attaching screen can tell it missed one. */
    @Volatile
    var revision: Int = 0
        private set

    @Synchronized
    fun pending(): List<Request> = waiting.toList()

    @Synchronized
    fun oldest(): Request? = waiting.firstOrNull()

    /** Claim the one local read-aloud announcement for an approval. */
    @Synchronized
    fun claimSpeech(id: String): Boolean = spoken.add(id)

    @Synchronized
    fun accept(event: JSONObject) {
        val id = event.optString("id")
        if (id.isEmpty() || waiting.any { it.id == id }) return
        waiting.addLast(
            Request(
                id = id,
                tool = event.optString("tool").ifEmpty { "tool" },
                reason = event.optString("reason"),
                subject = event.optString("subject"),
                detail = event.optString("detail"),
                cwd = event.optString("cwd"),
            )
        )
        revision++
    }

    /** Answered here, or by the watch, or timed out on the bridge. */
    @Synchronized
    fun resolve(id: String) {
        if (waiting.removeAll { it.id == id }) revision++
    }

    @Synchronized
    fun clear() {
        if (waiting.isNotEmpty()) {
            waiting.clear()
            revision++
        }
    }
}
