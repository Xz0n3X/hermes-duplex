package org.hermes.duplex

import java.util.Base64

/**
 * The contract between the phone and the watch.
 *
 * Both apps compile against this, so a path or a key can only be misspelled
 * in one place. Two apps agreeing on strings by convention is how a watch
 * ends up silently receiving nothing, with no error anywhere to explain it.
 *
 * Which API carries what is deliberate:
 *
 * - DataClient for state. It is a replicated key-value store: the watch gets
 *   the current value when it connects, whether or not it was listening when
 *   that value changed. Right for "which chat is open" and "what did it last
 *   say"; wrong for anything that must arrive exactly once.
 * - MessageClient for actions. One-shot, ordered, fire and forget. Right for
 *   "send this prompt", which must not be replayed on reconnect.
 *
 * No audio crosses this link. The watch listens and speaks with its own
 * engines and sends text, which is bytes -- rather than the ~32 KB/s each way
 * that streaming PCM over Bluetooth would cost, on a battery the size of a
 * coin.
 */
object WearLink {

    /** Sent by the watch. Actions, not state. */
    const val PATH_PROMPT = "/hermes/prompt"
    const val PATH_STOP = "/hermes/stop"
    const val PATH_NEW_CHAT = "/hermes/new-chat"
    const val PATH_OPEN_CHAT = "/hermes/open-chat"
    const val PATH_OPEN_PROJECT = "/hermes/open-project"
    const val PATH_DELETE_PROJECT = "/hermes/delete-project"

    /** Which agent answers. Payload is "hermes", or a CLI's id. */
    const val PATH_ENGINE = "/hermes/engine"

    /**
     * A tap answering "may it run this". Payload is "<id>|allow" or
     * "<id>|deny".
     *
     * A message rather than a data item: it is an event, it happens once,
     * and a data item that replayed on every reconnect would re-approve
     * something the wrist agreed to yesterday.
     */
    const val PATH_APPROVE = "/hermes/approve"

    /** Published by the phone. State, not events. */
    const val PATH_STATE = "/hermes/state"
    const val PATH_CHATS = "/hermes/chats"

    /** Keys inside the state item. */
    const val KEY_REPLY = "reply"
    const val KEY_TURN_STATE = "turn_state"
    const val KEY_SUGGESTIONS = "suggestions"
    const val KEY_CHAT_TITLE = "chat_title"
    const val KEY_CONNECTED = "connected"
    const val KEY_ENGINE = "engine"
    const val KEY_CLAUDE_AVAILABLE = "claude_available"

    /**
     * The command-line agents the bridge has, packed as id/label pairs.
     *
     * Sent rather than assumed, for the same reason the phone asks: which
     * ones exist is a fact about the bridge machine. A watch with a
     * hardcoded pair of choices misreads the engine the moment a third is
     * added -- it shows "off" for an agent it has never heard of, and the
     * next tap silently switches you to one it has.
     */
    const val KEY_AGENTS = "agents"

    /** The question the agent is blocked on, or empty for none. */
    const val KEY_APPROVAL_ID = "approval_id"
    const val KEY_APPROVAL_TOOL = "approval_tool"
    const val KEY_APPROVAL_SUBJECT = "approval_subject"

    /** The turns already in the open conversation. See packSaid. */
    const val KEY_HISTORY = "history"

    /**
     * Changes on every publish.
     *
     * DataClient does not notify listeners when an item is rewritten with
     * identical bytes, so asking the same question twice would leave the
     * watch showing the first answer with no sign the second had arrived.
     */
    const val KEY_STAMP = "stamp"

    const val KEY_CHAT_LIST = "chat_list"
    const val KEY_PROJECT_LIST = "project_list"
    const val KEY_ACTIVE_PROJECT = "active_project"
    const val KEY_CHAT_ID = "chat_id"
    const val KEY_TEXT = "text"

    /**
     * One turn of a conversation, as the wrist needs it.
     *
     * Role and words, and nothing else. The watch has no use for ids,
     * timestamps or what a turn did -- that is a phone-sized question -- but
     * it very much needs the words, because a session opened with only the
     * newest answer on screen is a session you cannot follow.
     */
    data class Said(
        val fromYou: Boolean,
        val text: String,
        /**
         * What the agent ran during this turn: one short line each.
         *
         * Headers only -- the tool and what it was pointed at. The bodies are
         * kilobytes and live behind the phone's on-demand fetch; these are
         * what let a wrist tell "read three files" from "rewrote the config"
         * without one.
         */
        val steps: List<Step> = emptyList(),
        /** The phone can know this count before command bodies are fetched. */
        val detail: Int = 0,
    )

    /** The phone's two-level command detail, carried to the watch. */
    data class Step(
        val tool: String,
        val subject: String,
        val command: String,
        val output: String,
    )

    /** How many step lines ride with a turn, and how much of each. */
    const val STEP_LINES = 40
    const val STEP_CHARS = 240

    /**
     * How many turns are worth carrying to a watch.
     *
     * Raised from 12 when the bridge began sending each block of a reply as
     * its own turn rather than growing one. That is right for reading and for
     * speaking -- a block can be shown and said the moment it lands -- but it
     * means a single answer is now six or ten turns, not one. At twelve, one
     * reply filled the whole window and pushed the question that prompted it
     * out, so the thread on the wrist was all agent and no person.
     *
     * The cost is the packed payload on the phone route: turns x
     * HISTORY_CHARS, so 24 KB at worst against a 100 KB Data Layer item.
     */
    const val HISTORY_TURNS = 40

    /** And how much of any one of them. */
    const val HISTORY_CHARS = 600

    /**
     * Pack turns for the Data Layer, which takes strings and not objects.
     *
     * The role rides as a one-character prefix. Newlines separate turns and
     * are stripped from the text, which costs paragraph breaks on the wrist
     * -- a 45mm screen was never going to show them anyway.
     */
    fun packSaid(turns: List<Said>): String = buildString {
        turns.forEach { turn ->
            if (isNotEmpty()) append('\n')
            append(if (turn.fromYou) '>' else '<')
            // Keep paragraph breaks in the mirrored transcript. The line
            // protocol uses newlines as record separators, so encode the
            // message body and retain a raw-body fallback for old watches.
            append('~')
            append(encode(turn.text.take(HISTORY_CHARS)))
            // A step is its own line under the turn it belongs to. Given its
            // own prefix rather than folded into the text so the watch can
            // draw it differently -- and so a build that predates this shows
            // the turn without them instead of with them inlined.
            turn.steps.take(STEP_LINES).forEach { step ->
                append('\n').append('!')
                append(encode(step.tool)).append('|')
                append(encode(step.subject)).append('|')
                append(encode(step.command)).append('|')
                append(encode(step.output.take(STEP_CHARS * 4)))
            }
            if (turn.detail > turn.steps.size) {
                append('\n').append('?').append(turn.detail)
            }
        }
    }

    fun unpackSaid(packed: String?): List<Said> {
        if (packed.isNullOrEmpty()) return emptyList()
        val turns = mutableListOf<Said>()
        packed.split("\n").forEach { line ->
            if (line.length < 2) return@forEach
            val body = line.substring(1)
            when (line[0]) {
                // Attaches to the turn above it; one with nothing above is a
                // truncated payload and is dropped rather than guessed at.
                '!' -> turns.lastOrNull()?.let {
                    val fields = body.split('|')
                    val step = if (fields.size == 4) {
                        Step(
                            tool = decode(fields[0]),
                            subject = decode(fields[1]),
                            command = decode(fields[2]),
                            output = decode(fields[3]),
                        )
                    } else {
                        // Read histories produced by older watch builds.
                        Step("", body, "", "")
                    }
                    turns[turns.size - 1] = it.copy(steps = it.steps + step)
                }
                '?' -> turns.lastOrNull()?.let {
                    turns[turns.size - 1] = it.copy(detail = body.toIntOrNull() ?: it.detail)
                }
                else -> turns.add(
                    Said(
                        fromYou = line[0] == '>',
                        text = if (body.startsWith('~')) decode(body.substring(1)) else body,
                    )
                )
            }
        }
        return turns
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String = runCatching {
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    }.getOrDefault(value)

    /** What the watch shows while a turn is running. */
    object Turn {
        const val IDLE = "idle"
        const val LISTENING = "listening"
        const val THINKING = "thinking"
        const val WORKING = "working"
        const val SPEAKING = "speaking"
    }

    /**
     * Pairs travel as one string rather than as a nested list.
     *
     * A DataMap can hold a list of maps, but then the two sides must agree on
     * its shape as well as its key, and a shape mismatch fails silently the
     * same way a misspelled key does. The separators are ASCII unit and
     * record separators, which do not occur in prose.
     */
    private const val FIELD = "\u001F"
    private const val RECORD = "\u001E"

    fun packPairs(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(RECORD) { (first, second) -> "$first$FIELD$second" }

    fun unpackPairs(packed: String?): List<Pair<String, String>> {
        if (packed.isNullOrEmpty()) return emptyList()
        return packed.split(RECORD).mapNotNull { record ->
            val parts = record.split(FIELD)
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
        }
    }
}
