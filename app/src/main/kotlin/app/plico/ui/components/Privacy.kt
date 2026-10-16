package app.plico.ui.components

import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plico.app
import app.plico.data.Prefs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Tapjacking guard (DESIGN §12, apis AF8): drops a gesture that starts while another app's window
 * covers this one. Put it on Send, Delete and Show remote content only, never on the root, so that
 * screen-dimmer users can still use the app. FLAG_WINDOW_IS_PARTIALLY_OBSCURED is not checked
 * (false positives from system UI).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rejectObscuredTouches(): Modifier = pointerInteropFilter { event ->
    event.actionMasked == MotionEvent.ACTION_DOWN && (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
}

/**
 * Asks the keyboard not to learn from the text fields in [content] (IME_FLAG_NO_PERSONALIZED_LEARNING)
 * while Prefs.incognitoKeyboard is on (DESIGN §12). Wrap the compose and search fields with it.
 */
@Composable
fun PrivateTextInput(content: @Composable () -> Unit) {
    val incognito by rememberPref(initial = true) { incognitoKeyboard }
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            if (!incognito) {
                nextHandler.startInputMethod(request)
            } else {
                nextHandler.startInputMethod(
                    PlatformTextInputMethodRequest { info ->
                        request.createInputConnection(info).also {
                            info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        }
                    },
                )
            }
        },
        content = content,
    )
}

/**
 * A setting from [Prefs] as Compose state, read again after every settings change. [initial] is shown
 * until the first read; pick the private choice.
 */
@Composable
fun <T> rememberPref(initial: T, read: Prefs.() -> T): State<T> {
    if (LocalInspectionMode.current) return remember { mutableStateOf(initial) }
    val prefs = LocalContext.current.app.prefs
    val values = remember(prefs) { prefs.changes().map { prefs.read() }.distinctUntilChanged() }
    return values.collectAsStateWithLifecycle(initial)
}
