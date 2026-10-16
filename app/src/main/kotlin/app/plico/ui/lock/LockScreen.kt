package app.plico.ui.lock

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.plico.R
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.components.SystemPages

private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

/**
 * Covers the whole UI while [AppLock.locked] (DESIGN §12, privacy PS15). It shows the system prompt
 * (platform BiometricPrompt: strong biometric or the screen lock, no negative button) when it appears
 * and after the system closed the prompt, and calls [onUnlocked] after success. Without a screen lock
 * on the phone it opens the Android settings to set one; there is no way around the lock.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current ?: return
    val title = stringResource(R.string.ui_lock_prompt_title)
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    val prompt = remember(activity) { Prompt(activity, title) { currentOnUnlocked() } }
    LifecycleResumeEffect(prompt) {
        prompt.onResume()
        onPauseOrDispose {}
    }
    DisposableEffect(prompt) { onDispose(prompt::cancel) }

    Surface(modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Lock,
            title = stringResource(R.string.ui_lock_title),
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            message = if (prompt.available) prompt.error else stringResource(R.string.ui_lock_no_screen_lock),
            actionLabel = stringResource(if (prompt.available) R.string.ui_lock_unlock else R.string.ui_lock_set_screen_lock),
            onAction = { if (prompt.available) prompt.show() else SystemPages.screenLock(activity) },
        )
    }
}

/** One system prompt at a time; its result arrives on the main thread. */
private class Prompt(private val activity: Activity, private val title: String, private val onSuccess: () -> Unit) {
    var available by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var pending: CancellationSignal? = null

    // Show by itself only once, and again after the system closed it. A prompt the user closed waits
    // for the button, so that Back on the screen-lock page cannot loop.
    private var auto = true

    /** Checks again for a screen lock (the user may have set one) and shows the prompt when due. */
    fun onResume() {
        checkAvailable()
        if (auto && available) {
            auto = false
            show()
        }
    }

    fun show() {
        checkAvailable()
        if (!available || pending != null) return
        val signal = CancellationSignal().also { pending = it }
        error = null
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
            .authenticate(signal, activity.mainExecutor, Callback())
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    private fun checkAvailable() {
        available = activity.getSystemService(BiometricManager::class.java)
            .canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private inner class Callback : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            pending = null
            onSuccess()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            pending = null
            when (errorCode) {
                BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> auto = true
                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> Unit
                else -> error = errString.toString()
            }
        }
    }
}
