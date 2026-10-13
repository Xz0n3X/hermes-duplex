package org.hermes.duplex.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.hermes.duplex.OrbModel
import org.hermes.duplex.WearLink

/**
 * What the watch is showing, in one place.
 *
 * Small enough to be plain observable state rather than a ViewModel: there
 * is no configuration change worth surviving on a watch, and nothing here
 * outlives the screen.
 */
class WearState {

    /**
     * The turns before the one on screen, oldest first.
     *
     * The watch used to hold exactly one answer, so opening a past session
     * showed nothing at all and a finished exchange vanished the moment the
     * next question went out. Neither is usable for following an agent that
     * works for minutes at a time.
     */
    var history by mutableStateOf<List<WearLink.Said>>(emptyList())

    var reply by mutableStateOf("")
    var heard by mutableStateOf("")
    var chatTitle by mutableStateOf("")
    var suggestions by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var chats by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var projects by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var activeChatId by mutableStateOf("")
    var activeProject by mutableStateOf("")
    var listening by mutableStateOf(false)
    var notice by mutableStateOf("")

    /** Which agent the bridge is handing turns to: "hermes", or a CLI's id. */
    var engine by mutableStateOf("hermes")

    /**
     * A question the agent is blocked on, or empty.
     *
     * Held rather than shown-and-forgotten: it stays until the bridge says
     * it is gone, which happens when this watch answers, when the phone
     * does, or when nobody does for three minutes.
     */
    var approvalId by mutableStateOf("")
    var approvalTool by mutableStateOf("")
    var approvalSubject by mutableStateOf("")

    /** Whether the bridge machine has the claude CLI at all. */
    var claudeAvailable by mutableStateOf(false)

    /** The command-line agents the bridge has, id to label. */
    var agents by mutableStateOf<List<Pair<String, String>>>(emptyList())

    /** Whether a phone running the other half of this is in range. */
    var phoneReachable by mutableStateOf(true)

    /** Whether that phone has a bridge session. */
    var bridgeConnected by mutableStateOf(false)

    var turn by mutableStateOf(WearLink.Turn.IDLE)

    /**
     * Whether a reply has been seen since the link last started.
     *
     * The Data Layer replays whatever it currently holds the moment a
     * listener attaches, which is right for showing state and wrong for
     * speaking it: every time the screen woke and the link restarted, the
     * last answer arrived looking brand new and got read out again from the
     * top. The first state after a start is caught up with silently.
     */
    private var primed = false

    /** Whether a question is out and its answer has not landed yet. */
    private var waiting = false

    /** The answer that was on screen when that question was asked. */
    private var previous = ""

    /** Whether the bridge has visibly gone to work on it since. */
    private var worked = false


    /** The reply most recently read aloud, so the same one is not repeated. */
    private var spokenAlready = ""

    /** Called when the link (re)connects, so the next state is catch-up. */
    fun resetSpeech() {
        primed = false
    }

    /**
     * Take a published state, and return the reply if it is worth speaking.
     *
     * The caller reads that aloud. Returning it rather than speaking here
     * keeps this class free of anything that makes a sound, which is what
     * lets it be read in one sitting.
     */
    fun apply(state: PhoneLink.State): String {
        bridgeConnected = state.connected
        turn = state.turn
        engine = state.engine
        claudeAvailable = state.claudeAvailable
        if (state.agents.isNotEmpty()) agents = state.agents
        approvalId = state.approvalId
        approvalTool = state.approvalTool
        approvalSubject = state.approvalSubject
        notice = ""

        // Opening a different conversation abandons whatever was in flight,
        // and brings that conversation's own transcript with it.
        if (state.chatTitle != chatTitle) {
            settle()
            history = state.history
            reply = ""
        } else if (state.history.size > history.size ||
                   (state.history.size == history.size &&
                    state.history != history)) {
            // A link republishes its cache constantly, so a *shorter*
            // transcript than the one already held is refused -- otherwise a
            // stale publish would roll the thread backwards mid-turn.
            //
            // Same length with different words is not stale, though, and
            // taking only longer ones was why a followed session went quiet
            // the moment it mattered: a reply is written to the transcript
            // over many seconds and grows the *last* turn rather than adding
            // one, so every update while an agent was actually answering had
            // exactly the length that this test threw away.
            history = state.history
        }
        chatTitle = state.chatTitle

        // A link publishes its entire cached state on every event it gets,
        // and between asking and answering that cache still holds the last
        // answer and the last answer's suggestions. So the screen cleared on
        // the tap and the very next "thinking" event put the old answer
        // straight back -- which looked exactly like the tap doing nothing.
        //
        // While a question is in flight, nothing that is merely the previous
        // answer coming round again is allowed on screen.
        if (waiting) {
            if (state.turn == WearLink.Turn.THINKING ||
                state.turn == WearLink.Turn.SPEAKING
            ) {
                worked = true
            }
            // Identical wording to last time is still a new answer, as long
            // as the bridge visibly went away and thought about it.
            val answered = state.reply.isNotBlank() &&
                (state.reply != previous || (worked && state.turn == WearLink.Turn.IDLE))
            if (!answered) {
                suggestions = emptyList()
                return ""
            }
            settle()
        }

        suggestions = state.suggestions
        val changed = state.reply.isNotBlank() && state.reply != reply
        if (changed) {
            // The exchange just finished joins the thread, so scrolling back
            // during a session shows what has been happening rather than
            // only the newest answer.
            // On the phone route, however, `state.history` already contains
            // both records because it is published from ConversationStore.
            // Appending them again here made a new chat show the first prompt
            // two or three times. Add only records absent from the mirrored
            // tail; direct Wi-Fi still gets the same history reconstruction.
            if (!history.takeLast(4).any { it.fromYou && it.text == heard }) {
                remember(WearLink.Said(fromYou = true, text = heard))
            }
            if (!history.takeLast(4).any { !it.fromYou && it.text == state.reply }) {
                remember(WearLink.Said(fromYou = false, text = state.reply))
            }
        }
        reply = state.reply

        // Catching up is not the same as being told. The first state after a
        // start is whatever was already there.
        // Followed CLI replies grow in place. Return only the suffix that
        // arrived since the last publication; otherwise every poll queues the
        // whole answer again and the wrist never catches up.
        val fresh = when {
            !changed -> ""
            state.reply.startsWith(spokenAlready) ->
                state.reply.substring(spokenAlready.length)
            else -> state.reply
        }
        val speak = fresh.isNotBlank() && primed
        primed = true
        if (changed) spokenAlready = state.reply
        return if (speak) fresh else ""
    }

    /**
     * A question has just gone out; show it, and clear what it replaces.
     *
     * Every way of asking ends up here -- spoken, or a tapped suggestion --
     * so the screen looks the same however the question was arrived at: the
     * question up top, the old answer gone the instant the tap lands rather
     * than when the new one finally arrives, and the old suggestions gone
     * with it, since they were offers about a different answer.
     */
    fun asking(text: String) {
        heard = text
        previous = reply
        waiting = true
        worked = false
        reply = ""
        suggestions = emptyList()
        turn = WearLink.Turn.THINKING
        notice = ""
    }

    /** Add a turn, unless it is already the last thing in the thread. */
    private fun remember(turn: WearLink.Said) {
        if (turn.text.isBlank()) return
        if (history.lastOrNull()?.text == turn.text) return
        history = (history + turn).takeLast(WearLink.HISTORY_TURNS)
    }

    private fun settle() {
        waiting = false
        worked = false
        previous = ""
    }

    /** The open project's display name, for the header on the chats page. */
    val activeProjectName: String
        get() = projects.firstOrNull { it.first == activeProject }?.second ?: ""

    /** Say it again, on request. Bypasses the once-only rule deliberately. */
    fun replyToRepeat(): String = reply

    fun note(text: String) {
        notice = text
    }

    /**
     * Which orb to draw.
     *
     * Listening is the watch's own microphone, not the bridge's -- on the
     * phone those are the same thing, here they are not, and the one the
     * user is looking at is the one in front of them.
     */
    val orbState: OrbModel.State
        get() = when {
            listening -> OrbModel.State.LISTENING
            // The watch's own voice, not the bridge's opinion of the turn.
            // A followed session publishes SPEAKING for every block it writes
            // and has no event for "and now it has stopped", so an orb driven
            // from that flag stayed lit as speaking long after the watch had
            // gone quiet. The synthesiser here knows exactly when it is
            // talking, and it is the thing making the sound.
            org.hermes.duplex.Speaker.reading -> OrbModel.State.SPEAKING
            turn == WearLink.Turn.WORKING -> OrbModel.State.WORKING
            // A followed CLI publishes SPEAKING while it is still writing
            // output. That is work in progress on this watch even when this
            // watch is not reading audio, so it must not fall back to idle.
            turn == WearLink.Turn.SPEAKING -> OrbModel.State.SPEAKING
            // Keep unknown non-idle states animated too. The bridge can gain
            // a more specific working phase without making older watches
            // look frozen until their client is updated.
            turn != WearLink.Turn.IDLE -> OrbModel.State.THINKING
            else -> OrbModel.State.IDLE
        }

    /** One line saying what it is doing, or what is wrong. */
    val status: String
        get() = when {
            notice.isNotBlank() -> notice
            !phoneReachable -> "Phone not connected"
            !bridgeConnected -> "Phone is not connected to Hermes"
            listening -> "Listening\u2026"
            org.hermes.duplex.Speaker.reading -> "Speaking\u2026"
            turn == WearLink.Turn.THINKING -> "Thinking\u2026"
            turn == WearLink.Turn.WORKING -> "Working\u2026"
            turn == WearLink.Turn.SPEAKING -> "Working\u2026"
            turn != WearLink.Turn.IDLE -> "Working\u2026"
            // Nothing, when there is nothing to say. The button above it
            // already reads "Tap to talk", and printing that twice is one
            // line of a small screen spent saying it again.
            else -> ""
        }

    /** Whether anything can usefully be asked right now. */
    val usable: Boolean
        get() = phoneReachable && bridgeConnected
}
