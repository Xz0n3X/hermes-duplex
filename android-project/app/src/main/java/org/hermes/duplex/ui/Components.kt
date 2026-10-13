package org.hermes.duplex.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.hermes.duplex.LinkMovement
import org.hermes.duplex.Markdown
import org.hermes.duplex.ui.theme.Palette

/** The palette colour a [Tone] stands for. */
@Composable
fun Tone.color(): Color {
    val p = Palette
    return when (this) {
        Tone.OFFLINE -> p.stateOffline
        Tone.NEUTRAL -> p.textTertiary
        Tone.RUNNING -> p.statusRunning
        Tone.HEARING -> p.stateHearing
        Tone.HELD -> p.stateHeld
        Tone.SENT -> p.stateSent
        Tone.THINKING -> p.stateThinking
        Tone.SPEAKING -> p.stateSpeaking
        Tone.ERROR -> p.stateError
        Tone.WARNING -> p.statusWarning
        Tone.ACCENT -> p.accent
    }
}

/** A rounded, bordered surface: the card every settings group sits on. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(12.dp),
    content: @Composable () -> Unit,
) {
    val p = Palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(p.bgRaised, RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, p.borderDefault), RoundedCornerShape(14.dp))
            .padding(padding),
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Palette.textTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Palette.textTertiary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = modifier.padding(vertical = 2.dp),
    )
}

/** The solid action button of the design: rounded, accent-filled. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.accent,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = Palette.bgOverlay,
            disabledContentColor = Palette.textTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) { Text(text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A quiet, outlined button for secondary actions. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.textPrimary,
) {
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .border(BorderStroke(1.dp, Palette.borderDefault), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = Palette.textPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Palette.bgOverlay,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedBorderColor = Palette.borderDefault,
            ),
        )
    }
}

/** An icon from the app's one set: single tone, tinted where it is used. */
@Composable
fun Ico(
    @androidx.annotation.DrawableRes id: Int,
    modifier: Modifier = Modifier,
    tint: Color = Palette.textTertiary,
    size: Dp = 20.dp,
    description: String? = null,
) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(id),
        contentDescription = description,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/** An [Ico] that can be tapped, with a touch target big enough for a thumb. */
@Composable
fun IconTap(
    @androidx.annotation.DrawableRes id: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.textTertiary,
    size: Dp = 20.dp,
    target: Dp = 40.dp,
) {
    Box(
        modifier
            .size(target)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Ico(id, tint = tint, size = size) }
}

/** A filled dot, as in the connection strip. */
@Composable
fun Dot(color: Color, size: Dp = 9.dp) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(size)
            .background(color, CircleShape)
    )
}

/**
 * Rich text for a message body.
 *
 * Hosted in a real TextView on purpose. The markdown renderer produces
 * Spannables (links, code spans, headings), and a TextView is what gives them
 * tappable links, text selection and long-press copy together --
 * [LinkMovement] exists because the usual link movement method costs
 * selection. Re-implementing all that in Compose would be a regression
 * dressed as a rewrite.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.textPrimary,
    sizeSp: Float = 14f,
    italic: Boolean = false,
    streaming: Boolean = false,
    render: Boolean = true,
    selectable: Boolean = true,
    fillWidth: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    linkColor: Color = Palette.accent,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    if (fillWidth) ViewGroup.LayoutParams.MATCH_PARENT
                    else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setLineSpacing(0f, 1.15f)
            }
        },
        update = { view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            view.setTextColor(color.toArgb())
            view.setLinkTextColor(linkColor.toArgb())
            view.typeface = Typeface.create(
                Typeface.DEFAULT, if (italic) Typeface.ITALIC else Typeface.NORMAL
            )
            view.maxLines = maxLines
            view.ellipsize = if (maxLines == Int.MAX_VALUE) null else android.text.TextUtils.TruncateAt.END
            val rendered: CharSequence = if (render) Markdown.render(text) else text
            // The caret marks text that is still arriving, so a reply that
            // stopped mid-sentence is not mistaken for one that finished there.
            view.text = if (streaming) {
                android.text.SpannableStringBuilder(rendered).append(" ▋")
            } else {
                rendered
            }
            view.setTextIsSelectable(selectable)
            LinkMovement.install(view)
        },
    )
}

/** The frame every secondary screen shares: a back arrow, a title, a scrolling body. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bgApp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTap(
                org.hermes.duplex.R.drawable.ic_back, "Back", onBack,
                modifier = Modifier.padding(horizontal = 4.dp),
                tint = Palette.textPrimary, size = 24.dp, target = 48.dp,
            )
            Text(
                title,
                color = Palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            content = content,
        )
    }
}

/** A closed dropdown that opens a menu of [items]. */
@Composable
fun Dropdown(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.bgRaised)
                .border(BorderStroke(1.dp, Palette.borderDefault), RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = items.getOrNull(selected) ?: placeholder,
                color = Palette.textPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Ico(org.hermes.duplex.R.drawable.ic_expand_more)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; onSelect(index) },
                )
            }
        }
    }
}

/** A slider whose value is committed when the finger lifts, not on every pixel. */
@Composable
fun CommitSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onLabel: @Composable (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Text(onLabel(shown), color = Palette.textPrimary, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    Slider(
        value = shown.coerceIn(range),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let(onCommit)
            dragging = null
        },
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = Palette.accent,
            activeTrackColor = Palette.accent,
            inactiveTrackColor = Palette.borderDefault,
        ),
        modifier = Modifier.semantics { contentDescription = label },
    )
}
