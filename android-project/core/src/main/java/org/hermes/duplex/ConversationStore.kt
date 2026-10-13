package org.hermes.duplex

import org.json.JSONObject

/**
 * The conversation, as a list of messages.
 *
 * Kept here rather than in the activity for the same reason [TimingStore]
 * is: the activity detaches its callbacks in onPause, and the service keeps
 * the session running without it. A reply that arrived while the screen was
 * off, or while the Settings screen was open, has to still be here when the
 * conversation comes back on screen -- under the old two-TextView UI it was
 * simply gone.
 *
 * In memory only. This is a conversation you are having, not a transcript
 * archive; Hermes keeps the history that matters on the server side.
 */
object ConversationStore {

    /**
     * SYSTEM is the thread talking about itself rather than a turn: what a
     * recall actually pulled in, for instance. Rendered as a dim centred line
     * with no role label, because it is not something anybody said.
     */
    enum class Role { YOU, HERMES, SYSTEM, IDEA }

    data class Message(
        val role: Role,
        val text: String,
        val at: Long,
        /** The reply was cut off by barge-in. */
        val interrupted: Boolean = false,
        /** Still being streamed; the bubble shows a caret while true. */
        val streaming: Boolean = false,
        /** One-line timing, attached when the turn's trajectory arrives. */
        val timing: String? = null,
        /**
         * What this reply offered, kept with the reply rather than globally.
         *
         * The strip above the composer shows the newest set, but the offers
         * belong to the answer that prompted them — scroll up three replies
         * and its lightbulb still brings back its own three, which is how
         * they work on the web.
         */
        val suggestions: List<Pair<String, String>> = emptyList(),
        /**
         * The turn's id in the Claude CLI's transcript, when it came from one.
         *
         * Empty for anything said in this app: only a browsed session has
         * turns that exist on disk with something more behind them.
         */
        val turnId: String = "",
        /**
         * How many steps this turn took — files read, commands run.
         *
         * A count, because that is all the history carries: what each step
         * did is fetched from the bridge on the tap that opens it. Some of
         * these transcripts are eighteen megabytes and nearly all of that
         * weight is tool output nobody asked to see.
         */
        val detail: Int = 0,
        /** Those steps, once fetched. Empty until then. */
        val steps: List<Step> = emptyList(),
        /** Whether the list of steps is showing at all. */
        val stepsOpen: Boolean = false,
        /** True between asking for them and getting them. */
        val detailLoading: Boolean = false,
        /** Private ideas start as a compact row and open only on tap. */
        val ideaOpen: Boolean = false,
        /** Structured search results delivered alongside the assistant text. */
        val media: List<Media> = emptyList(),
    ) {
        /** Stable identity shared by the row, auto-read, and its play button. */
        val speechKey: String
            get() = turnId.ifEmpty { "local:$at:$text" }
    }

    data class Media(
        val image: String = "",
        val thumbnail: String = "",
        val video: String = "",
        val title: String = "",
        val source: String = "",
        val platform: String = "",
        val duration: String = "",
    )

    /**
     * One thing a turn did, and what came back.
     *
     * Two levels, as the Claude app has it: the list of steps is one tap, and
     * what any single step *returned* is another. A `Read` of a large file
     * answers with the file; opening five of those at once to see which
     * command ran is the wall this replaced.
     */
    data class Step(
        val tool: String,
        val subject: String,
        val output: String,
        val expanded: Boolean = false,
        /** The whole of what was run, shown when the step is opened. */
        val command: String = "",
    )

    /** Enough to scroll back through a long session without unbounded growth. */
    private const val CAPACITY = 200

    private val messages = ArrayDeque<Message>()
    /** Server chat id whose local-only idea cards are currently attached. */
    private var localIdeaChatId: String = ""

    /** Bumped on every change, so a re-attaching screen can tell it missed some. */
    @Volatile
    var revision: Int = 0
        private set

    @Synchronized
    fun snapshot(): List<Message> = messages.toList()

    @Synchronized
    fun lastAssistant(): Message? = messages.lastOrNull { it.role == Role.HERMES }

    @Synchronized
    fun clear() {
        messages.clear()
        localIdeaChatId = ""
        revision++
    }

    /**
     * Replace the thread with a chat's stored messages.
     *
     * Called when a chat is opened, including the one opened on connect. The
     * bridge is the record of what was said -- it has the jsonl -- so this
     * takes what it sends rather than trying to reconcile with whatever was
     * on screen.
     */
    @Synchronized
    fun load(event: org.json.JSONObject) {
        val chatId = event.optString("id")
        val keepIdeas = if (chatId == localIdeaChatId) {
            messages.filter { it.role == Role.IDEA }
        } else {
            emptyList()
        }
        messages.clear()
        localIdeaChatId = chatId
        val stored = event.optJSONArray("messages")
        if (stored != null) {
            for (index in 0 until stored.length()) {
                val record = stored.optJSONObject(index) ?: continue
                val text = record.optString("content")
                if (text.isBlank()) continue
                val role = when (record.optString("role")) {
                    "user" -> Role.YOU
                    "assistant" -> Role.HERMES
                    else -> continue
                }
                val at = (record.optDouble("created_at", 0.0) * 1000).toLong()
                // The offers a reply made are stored with it, so its
                // lightbulb still works in a conversation reopened tomorrow.
                val offers = mutableListOf<Pair<String, String>>()
                record.optJSONArray("suggestions")?.let { stored ->
                    for (slot in 0 until stored.length()) {
                        val offer = stored.optJSONObject(slot) ?: continue
                        val label = offer.optString("label")
                        if (label.isEmpty()) continue
                        offers += label to offer.optString("detail").ifEmpty { label }
                    }
                }
                append(Message(
                    role, text,
                    if (at > 0) at else System.currentTimeMillis(),
                    suggestions = offers,
                    turnId = record.optString("id"),
                    detail = record.optInt("detail"),
                    steps = stepsOf(record),
                    // History restores the steps themselves, never the
                    // expanded UI state. A reopened conversation should be
                    // compact until the user taps its detail row.
                    stepsOpen = false,
                ))
            }
        }
        // Ideas are private Android-side messages, so the bridge does not
        // include them in its transcript. Reattach them when the same chat
        // is refreshed or the socket reconnects; a different chat starts
        // with a clean private-idea section.
        keepIdeas.forEach { append(it) }
        revision++
    }

    /**
     * Fold in turns from a session someone else is driving.
     *
     * The bridge tails the open CLI transcript and sends what changed. Two
     * things arrive here and only one of them is new: a turn nobody has seen,
     * and the *last* turn again because it grew. One reply is written to the
     * transcript as several records -- text, a tool call, its result, more
     * text -- so the turn holding it keeps growing until the reply is done,
     * and replacing it by id is what makes it read as live rather than as a
     * column of fragments.
     *
     * What the person has opened survives the replacement. A turn re-sent
     * because it gained a step must not collapse the steps they were reading.
     */
    @Synchronized
    fun merge(event: org.json.JSONObject): List<Pair<String, String>> {
        val moved = mutableListOf<Pair<String, String>>()
        val stored = event.optJSONArray("messages") ?: return moved
        for (index in 0 until stored.length()) {
            val record = stored.optJSONObject(index) ?: continue
            val text = record.optString("content")
            if (text.isBlank()) continue
            val role = when (record.optString("role")) {
                "you", "user" -> Role.YOU
                "assistant" -> Role.HERMES
                else -> continue
            }
            val id = record.optString("id")
            val at = (record.optDouble("created_at", 0.0) * 1000).toLong()
            val incoming = Message(
                role, text,
                if (at > 0) at else System.currentTimeMillis(),
                turnId = id,
                detail = record.optInt("detail"),
                steps = stepsOf(record),
            )
            // Reported to the caller so it can read the new part out. Only
            // the agent's words: reading your own question back to you is
            // not monitoring, and on a followed session you did not ask it.
            if (role == Role.HERMES) moved += id to text
            val known = messages.indexOfLast { it.turnId.isNotEmpty() && it.turnId == id }
            if (known >= 0) {
                val was = messages[known]
                messages[known] = incoming.copy(
                    suggestions = was.suggestions,
                    steps = if (incoming.steps.isNotEmpty()) incoming.steps else was.steps,
                    stepsOpen = was.stepsOpen,
                )
                continue
            }
            // A turn we streamed ourselves has no transcript id yet: this is
            // the same words arriving from the file the CLI just wrote. Adopt
            // it rather than appending, or a turn driven from this phone
            // shows up twice -- once as it was spoken, once as it was
            // recorded.
            val tail = messages.lastOrNull()
            if (tail != null && tail.role == role && tail.turnId.isEmpty()) {
                messages[messages.size - 1] =
                    incoming.copy(
                        suggestions = tail.suggestions,
                        steps = if (incoming.steps.isNotEmpty()) incoming.steps else tail.steps,
                        stepsOpen = tail.stepsOpen,
                    )
            } else {
                append(incoming)
            }
        }
        revision++
        return moved
    }

    /** Parse the bounded command headers carried with CLI session messages. */
    private fun stepsOf(record: JSONObject): List<Step> {
        val list = record.optJSONArray("steps") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            Step(
                tool = item.optString("tool"),
                subject = item.optString("subject"),
                output = item.optString("output"),
                command = item.optString("command"),
            )
        }
    }

    /** A note about the conversation, not a turn in it. */
    @Synchronized
    fun note(text: String) {
        if (text.isBlank()) return
        append(Message(Role.SYSTEM, text, System.currentTimeMillis()))
    }

    /** A private local-LLM idea, deliberately separate from the agent thread. */
    @Synchronized
    fun idea(text: String) {
        if (text.isBlank()) return
        append(Message(Role.IDEA, text, System.currentTimeMillis()))
    }

    /** Expand or collapse one private idea without changing the agent turn. */
    @Synchronized
    fun toggleIdea(at: Long) {
        val index = messages.indexOfFirst { it.role == Role.IDEA && it.at == at }
        if (index < 0) return
        messages[index] = messages[index].copy(ideaOpen = !messages[index].ideaOpen)
        revision++
    }

    /** Collapse every private flow before another screen element is used. */
    @Synchronized
    fun collapseIdeas(): Boolean {
        var changed = false
        messages.indices.forEach { index ->
            val message = messages.elementAt(index)
            if (message.role == Role.IDEA && message.ideaOpen) {
                messages[index] = message.copy(ideaOpen = false)
                changed = true
            }
        }
        if (changed) revision++
        return changed
    }

    /** An accepted transcript: what the user actually said this turn. */
    @Synchronized
    fun said(text: String) {
        if (text.isBlank()) return
        append(Message(Role.YOU, text, System.currentTimeMillis()))
    }

    /**
     * A streamed fragment of the reply.
     *
     * Opens a Hermes message on the first delta of a turn and grows it after
     * that, so the bubble fills in as the words are generated rather than
     * appearing whole a second after they were spoken.
     */
    @Synchronized
    fun delta(text: String) {
        if (text.isEmpty()) return
        val last = messages.lastOrNull()
        if (last != null && last.role == Role.HERMES && last.streaming) {
            messages[messages.size - 1] = last.copy(text = last.text + text)
            revision++
        } else {
            append(Message(Role.HERMES, text, System.currentTimeMillis(), streaming = true))
        }
    }

    /** The finished reply. Its text is authoritative over the accumulated deltas. */
    @Synchronized
    fun completed(text: String) {
        val last = messages.lastOrNull()
        if (last != null && last.role == Role.HERMES && last.streaming) {
            messages[messages.size - 1] =
                last.copy(text = text.ifBlank { last.text }, streaming = false)
            revision++
        } else if (text.isNotBlank()) {
            append(Message(Role.HERMES, text, System.currentTimeMillis()))
        }
    }

    /** Attach structured search results to the current assistant turn. */
    @Synchronized
    fun media(rows: List<Media>) {
        if (rows.isEmpty()) return
        val last = messages.lastOrNull() ?: return
        if (last.role != Role.HERMES) return
        messages[messages.size - 1] = last.copy(media = rows)
        revision++
    }

    /** Barge-in cut the reply short. Mark it rather than dropping it. */
    @Synchronized
    fun interrupted() {
        val last = messages.lastOrNull() ?: return
        if (last.role != Role.HERMES) return
        messages[messages.size - 1] = last.copy(streaming = false, interrupted = true)
        revision++
    }

    /** Hang a set of offers off the reply that made them. */
    @Synchronized
    fun offer(suggestions: List<Pair<String, String>>) {
        val last = messages.lastOrNull() ?: return
        if (last.role != Role.HERMES) return
        messages[messages.size - 1] = last.copy(suggestions = suggestions)
        revision++
    }

    /**
     * Mark a turn as waiting for its steps, or fill them in.
     *
     * Addressed by transcript id rather than by position: the fetch is a
     * round trip to the bridge and back, and by the time it lands the thread
     * may have scrolled, grown, or evicted its head.
     */
    @Synchronized
    fun detail(turnId: String, steps: List<Step>, loading: Boolean) {
        edit(turnId) {
            it.copy(steps = steps, stepsOpen = steps.isNotEmpty(),
                    detailLoading = loading)
        }
    }

    /** Close the list of steps without forgetting what it holds. */
    @Synchronized
    fun collapseSteps(turnId: String) {
        edit(turnId) { it.copy(stepsOpen = false) }
    }

    /** Open the list again, if it was fetched before. */
    @Synchronized
    fun expandSteps(turnId: String) {
        edit(turnId) { it.copy(stepsOpen = true) }
    }

    /** Show or hide what one step returned. */
    @Synchronized
    fun toggleStep(turnId: String, index: Int) {
        edit(turnId) { message ->
            if (index !in message.steps.indices) return@edit message
            val steps = message.steps.toMutableList()
            steps[index] = steps[index].copy(expanded = !steps[index].expanded)
            message.copy(steps = steps)
        }
    }

    /** File one step's output once it has been fetched. */
    @Synchronized
    fun stepOutput(turnId: String, index: Int, output: String) {
        edit(turnId) { message ->
            if (index !in message.steps.indices) return@edit message
            val steps = message.steps.toMutableList()
            steps[index] = steps[index].copy(output = output)
            message.copy(steps = steps)
        }
    }

    private fun edit(turnId: String, change: (Message) -> Message) {
        if (turnId.isEmpty()) return
        val index = messages.indexOfFirst { it.turnId == turnId }
        if (index < 0) return
        messages[index] = change(messages[index])
        revision++
    }

    /** Hang the turn's timing off the reply it describes. */
    @Synchronized
    fun timing(line: String) {
        val last = messages.lastOrNull() ?: return
        if (last.role != Role.HERMES) return
        messages[messages.size - 1] = last.copy(timing = line)
        revision++
    }

    private fun append(message: Message) {
        while (messages.size >= CAPACITY) messages.removeFirst()
        messages.addLast(message)
        revision++
    }
}
