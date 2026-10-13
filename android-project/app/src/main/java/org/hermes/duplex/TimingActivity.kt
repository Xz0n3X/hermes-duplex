package org.hermes.duplex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hermes.duplex.ui.Note
import org.hermes.duplex.ui.ScreenScaffold
import org.hermes.duplex.ui.SoftButton
import org.hermes.duplex.ui.theme.HermesTheme
import org.hermes.duplex.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where each turn's time went, as bars.
 *
 * Stages are drawn as separate rows, not a stack, because they are nested:
 * tts and speak happen *inside* the model span, so stacking them end to end
 * would show a total that never happened. Each bar is a fraction of the
 * turn's own elapsed time, and the numbers are the truth.
 */
class TimingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HermesTheme { TimingScreen { finish() } } }
    }
}

private val STAGE_COLOURS = mapOf(
    "stt" to Color(0xFF7E57C2),      // hearing you
    "recall" to Color(0xFF78909C),   // memory lookup
    "model" to Color(0xFF1E88E5),    // the wait
    "tts" to Color(0xFFFB8C00),      // making the audio
    "speak" to Color(0xFF43A047),    // audio playing, not a stall
)

@Composable
private fun TimingScreen(onBack: () -> Unit) {
    var turns by remember { mutableStateOf(TimingStore.recent()) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    ScreenScaffold(
        title = stringResource(R.string.timing),
        onBack = onBack,
        actions = {
            SoftButton(stringResource(R.string.refresh), { turns = TimingStore.recent() })
            SoftButton(
                stringResource(R.string.clear), { TimingStore.clear(); turns = TimingStore.recent() },
                modifier = Modifier.padding(start = 6.dp, end = 8.dp),
            )
        },
    ) {
        if (turns.isEmpty()) {
            Text(stringResource(R.string.no_turns_yet), color = Palette.textTertiary, fontSize = 14.sp)
            return@ScreenScaffold
        }
        val slowest = turns.maxOf { it.elapsed }.coerceAtLeast(0.001)
        Text(
            stringResource(
                R.string.timing_headline, turns.size,
                turns.map { it.thinking }.average(), turns.minOf { it.thinking },
            ),
            color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
        Note(stringResource(R.string.timing_key))
        turns.forEach { turn ->
            HorizontalDivider(Modifier.padding(top = 10.dp), color = Palette.borderDefault)
            Text(
                "${clock.format(Date(turn.at))}   ${"%.1f".format(turn.elapsed)}s" +
                    (turn.firstToken?.let { "   first word ${"%.1f".format(it)}s" } ?: ""),
                color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            turn.stages.filter { it.seconds >= 0.005 }.forEach { stage ->
                StageBar(stage.name, stage.seconds, slowest)
            }
        }
    }
}

/** One stage: name, a proportional bar, and the number. */
@Composable
private fun StageBar(name: String, seconds: Double, scale: Double) {
    val fraction = (seconds / scale).coerceIn(0.02, 1.0).toFloat()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f).height(16.dp)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(STAGE_COLOURS[name] ?: Color.Gray)
            )
        }
        Text(
            "${"%.2f".format(seconds)}s", color = Palette.textSecondary, fontSize = 13.sp,
            textAlign = TextAlign.End, modifier = Modifier.width(62.dp),
        )
    }
}
