package org.hermes.duplex.wear

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.hermes.duplex.Alerts
import org.hermes.duplex.EventLog
import org.hermes.duplex.Speaker
import org.hermes.duplex.WearLink

/**
 * The watch app: one screen for talking, one list for choosing which chat.
 *
 * Deliberately not a port of the phone. There are no projects, no settings,
 * no timing or debug screens -- those are things you do sitting down, and
 * a 45mm round screen is not where you do them. What is here is the part
 * worth having on a wrist: ask something, hear the answer, tap what to ask
 * next.
 */
class WearActivity : ComponentActivity() {

    private lateinit var speech: WearSpeech

    // Owned by WearSession, which outlives this screen. Read through it
    // rather than copied, so a rebuilt Activity binds to the live one.
    private val model: WearState get() = WearSession.model
    private val settings: WearSettings get() = WearSession.settings

    /**
     * The system voice-input screen, for watches with no recognition service.
     *
     * Registered unconditionally: which path is taken is decided when the
     * button is pressed, but a launcher has to exist before onCreate returns.
     */
    private val askByVoice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = speech.textFrom(result.data)
        model.listening = false
        if (text.isNotBlank()) asked(text)
    }

    private val askForBridge = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = speech.textFrom(result.data)
        if (text.isNotBlank()) {
            settings.rememberBridge(text)
            // Forced: the address itself changed, so the live socket is
            // pointed at the wrong place however unchanged the route is.
            WearSession.applyRoute(force = true)
        }
    }

    private val askForMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) speech.listen() else model.note("Microphone permission needed")
    }

    /**
     * Everything the app needs, asked for on the first run.
     *
     * Asked together rather than at the moment each is first used. The
     * microphone could wait for the first tap, but notifications cannot: the
     * one that matters arrives when a question is waiting and the screen is
     * off, which is exactly when there is nobody there to answer a permission
     * dialog. A watch that has not been granted this is a watch that stays
     * silent at the only moment it was worth wearing.
     */
    private val askForEverything = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        granted.forEach { (permission, ok) ->
            if (!ok) EventLog.add("not granted: ${permission.substringAfterLast('.')}")
        }
    }

    private fun needed(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.appVersion = BuildConfig.VERSION_NAME

        // Built before anything reads it, and kept alive by the service:
        // the screen is a view onto the connection, not its owner.
        WearSession.start(applicationContext)
        WearBridgeService.start(this)
        WearSession.onBusy = { keepAwakeWhileBusy() }

        speech = WearSpeech(this)

        needed().takeIf { it.isNotEmpty() }?.let { askForEverything.launch(it) }

        speech.onPartial = { heard -> model.heard = heard; model.notice = "" }
        speech.onFinal = { text ->
            model.listening = false
            asked(text)
        }
        speech.onListening = { model.listening = it }
        speech.onError = model::note

        setContent {
            WearApp(
                model = model,
                onTalk = ::talk,
                onSuggestion = ::asked,
                onEngine = { choice -> send(WearLink.PATH_ENGINE, choice) },
                onStop = {
                    speech.stop()
                    Speaker.stop()
                    send(WearLink.PATH_STOP)
                },
                onApprove = { id, allow ->
                    // Cleared here rather than waiting for the bridge to say
                    // so: the round trip is a second or two, and a prompt
                    // that stays up after the tap invites a second tap on
                    // whichever button is now under the finger.
                    model.approvalId = ""
                    send(WearLink.PATH_APPROVE, "$id|${if (allow) "allow" else "deny"}")
                },
                onNewChat = { send(WearLink.PATH_NEW_CHAT) },
                onOpenChat = { id -> send(WearLink.PATH_OPEN_CHAT, id) },
                onOpenProject = { path -> send(WearLink.PATH_OPEN_PROJECT, path) },
                onDeleteProject = { path -> send(WearLink.PATH_DELETE_PROJECT, path) },
                onRepeat = {
                    Speaker.stop()
                    Speaker.volume = settings.volume
                    Speaker.say(model.replyToRepeat())
                },
                settings = settings,
                onEditBridge = ::editBridge,
            )
        }
    }

    /** Spoken, or tapped from a suggestion: one way in. */
    private fun asked(text: String) = WearSession.ask(text)

    private fun talk() {
        // Whatever it was saying is not what you want to hear now.
        Speaker.stop()

        // The system screen owns the microphone while it is up, so it needs
        // no permission from us. Only the streaming path does.
        if (!speech.streaming) {
            model.notice = ""
            model.listening = true
            keepAwakeWhileBusy()
            askByVoice.launch(speech.voiceInputIntent())
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            speech.listen()
        } else {
            askForMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun send(path: String, payload: String = "") =
        WearSession.send(path, payload)

    /**
     * Type or dictate the bridge address.
     *
     * The same system input the rest of the watch uses, because a text field
     * of my own on a 45mm screen would be worse than the one the platform
     * already has -- and this is a thing typed once.
     */
    private fun editBridge() {
        askForBridge.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Bridge address")
            }
        )
    }

    override fun onStart() {
        super.onStart()
        // The route is a setting, and it may have been changed on the screen
        // that is now closing. Re-applied here so the choice takes effect on
        // the way back rather than on the next launch.
        WearSession.applyRoute()
    }

    /**
     * Stay awake while a turn is running.
     *
     * A watch blanks its screen after a few seconds, and on Wear that stops
     * the Activity -- which used to take the Data Layer listener and the
     * speech engine with it. So a question asked at the top of a twenty
     * second turn was answered to a phone that had nobody listening: the
     * reply arrived, and the watch had already stopped being able to hear
     * about it.
     *
     * Held only while there is something in flight. A watch that never
     * sleeps is a watch that is flat by the afternoon.
     */
    private fun keepAwakeWhileBusy() {
        val busy = model.listening || model.turn != WearLink.Turn.IDLE
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onStop() {
        super.onStop()
        // The microphone goes; the link does not.
        //
        // Screen off is not the same as done with. The answer to what was
        // just asked arrives seconds later and should still be spoken, and
        // tearing the listener down here is what stopped that happening.
        speech.stop()
    }

    override fun onPause() {
        super.onPause()
        keepAwakeWhileBusy()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The connection is not ours to close. It belongs to WearSession and
        // is held up by WearBridgeService, which is the entire point: the
        // answer to what was just asked arrives after this screen is gone.
        WearSession.onBusy = {}
        speech.stop()
    }
}
