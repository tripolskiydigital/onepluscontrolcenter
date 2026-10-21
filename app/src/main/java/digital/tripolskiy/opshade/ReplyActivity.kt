package digital.tripolskiy.opshade

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Inline reply. The keyboard can't show above the shade's accessibility overlay, so replying
 * happens in this small panel at the bottom of the screen: it fills the notification action's
 * RemoteInput and fires it, exactly as SystemUI's reply field does.
 */
class ReplyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_KEY)
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val action = key?.let { NotifListener.instance?.action(it, index) }
        val input = action?.remoteInputs?.firstOrNull { it.allowFreeFormInput }
        if (action == null || input == null) {
            finish()
            return
        }
        val item = NotifListener.items.value.flatMap { listOf(it) + it.children }.firstOrNull { it.key == key }
        setContent {
            CompositionLocalProvider(LocalPalette provides ShadePalette.from(this)) {
                Panel(item, action, input)
            }
        }
    }

    private fun send(action: Notification.Action, input: RemoteInput, text: String) {
        val results = Bundle().apply { putCharSequence(input.resultKey, text) }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(input), fill, results)
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        try { action.actionIntent.send(this, 0, fill) } catch (_: Exception) {}
        finish()
    }

    @androidx.compose.runtime.Composable
    private fun Panel(item: NotifItem?, action: Notification.Action, input: RemoteInput) {
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(P.card)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(16.dp),
            ) {
                Text(
                    item?.title?.ifBlank { null } ?: item?.header ?: "", color = P.text, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                item?.text?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = P.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        text, { text = it },
                        textStyle = TextStyle(color = P.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(P.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) send(action, input, text) }),
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .clip(RoundedCornerShape(24.dp))
                            .background(P.inactive)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        decorationBox = { inner ->
                            if (text.isEmpty()) Text(input.label?.toString() ?: action.title.toString(), color = P.textSecondary, fontSize = 16.sp)
                            inner()
                        },
                    )
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (text.isBlank()) P.inactive else P.active)
                            .clickable(enabled = text.isNotBlank()) { send(action, input, text) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (text.isBlank()) P.textSecondary else P.onActive)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_INDEX = "index"
    }
}
