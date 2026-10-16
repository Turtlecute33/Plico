package app.plico.ui.setup

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.plico.R
import app.plico.account.DeviceGoogleAuth
import app.plico.app
import app.plico.data.MailView
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.components.PrivateTextInput
import app.plico.ui.nav.Navigator
import app.plico.ui.nav.Route
import app.plico.ui.nav.sharedAxis

/**
 * Adds an account (DESIGN §13 Setup): Welcome → credentials or Gmail methods → connection test →
 * options → notifications and battery → unified inbox.
 */
@Composable
fun SetupScreen(nav: Navigator) {
    val app = LocalContext.current.app
    SetupFlow(viewModel { SetupViewModel(app, reauthId = null) }, nav)
}

/** Signs in again to account [accountId] with the method it uses; the stored secret is overwritten. */
@Composable
fun ReauthScreen(accountId: Long, nav: Navigator) {
    val app = LocalContext.current.app
    SetupFlow(viewModel { SetupViewModel(app, reauthId = accountId) }, nav, reauth = true)
}

/** A page, or the work that covers it. */
private data class Shown(val page: Page?, val busy: Busy?)

@Composable
private fun SetupFlow(vm: SetupViewModel, nav: Navigator, reauth: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    // Here and not on the Gmail page, which leaves the composition while the chooser is open.
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        activity?.let { vm.onAccountChosen(it, DeviceGoogleAuth.accountName(result.data)) }
    }
    val chooseAccount: () -> Unit = {
        vm.beginDevice()
        try {
            chooser.launch(DeviceGoogleAuth.chooseAccountIntent())
        } catch (_: ActivityNotFoundException) {
            if (activity != null) vm.onAccountChosen(activity, null)
        }
    }

    LaunchedEffect(state.done) {
        if (!state.done) return@LaunchedEffect
        if (reauth && nav.backStack.size > 1) nav.back() else nav.reset(Route.Mailbox(MailView.Unified))
    }

    // Back inside the flow follows the predictive gesture; saving cannot be interrupted.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = state.handlesBack && state.busy != Busy.SAVING) { events ->
        try {
            events.collect { backProgress = it.progress }
            vm.back()
        } finally {
            backProgress = 0f
        }
    }
    BackHandler(enabled = state.busy == Busy.SAVING) {}

    val showBack = state.busy != Busy.SAVING && state.page != Page.PERMISSIONS && state.page != Page.SIGNED_IN &&
        (state.handlesBack || nav.backStack.size > 1)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = { if (!vm.back()) nav.back() }) {
                            Icon(Icons.Back, contentDescription = stringResource(R.string.ui_back))
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        val motion = MaterialTheme.motionScheme
        val forward = sharedAxis<SetupState>(forward = true)
        val backward = sharedAxis<SetupState>(forward = false)
        PrivateTextInput {
            // Each page keeps the state it was shown with, so that a page that animates out does not change.
            AnimatedContent(
                targetState = state,
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .graphicsLayer {
                        val scale = 1f - 0.08f * backProgress
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.3f * backProgress
                    },
                transitionSpec = {
                    val from = initialState.shown(reauth).page
                    val to = targetState.shown(reauth).page
                    if (from == null || to == null || from == to) {
                        fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                    } else if (to > from) {
                        forward()
                    } else {
                        backward()
                    }
                },
                contentKey = { it.shown(reauth) },
                label = "setup",
            ) { shownState ->
                val shown = shownState.shown(reauth)
                when {
                    shown.page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                    shown.busy != null -> BusyView(shown.busy, onCancel = { vm.back() })
                    else -> when (shown.page) {
                        Page.WELCOME -> WelcomePage(onChoose = vm::choose)
                        Page.RISEUP -> RiseupPage(vm, shownState)
                        Page.GMAIL -> GmailPage(vm, shownState, onChooseAccount = chooseAccount)
                        Page.OWN_CLIENT -> OwnClientPage(vm, shownState)
                        Page.OPTIONS -> OptionsPage(vm, shownState)
                        Page.PERMISSIONS -> PermissionsPage(onDone = vm::finish)
                        Page.SIGNED_IN -> EmptyState(
                            icon = Icons.CheckCircle,
                            title = stringResource(R.string.setup_reauth_done, shownState.signedIn?.email.orEmpty()),
                            modifier = Modifier.fillMaxSize(),
                            actionLabel = stringResource(R.string.setup_done),
                            onAction = vm::finish,
                        )
                    }
                }
            }
        }
    }
}

/** What fills the screen; [page] null while "Sign in again" loads its account. Saving keeps the page. */
private fun SetupState.shown(reauth: Boolean) = Shown(
    page = page.takeUnless { reauth && this.reauth == null },
    busy = busy.takeIf { it != Busy.SAVING },
)

/** The connection test, the browser or Google's consent screen is in front; Cancel returns to the page. */
@Composable
private fun BusyView(busy: Busy, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator()
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(
                when (busy) {
                    Busy.BROWSER -> R.string.setup_busy_browser
                    Busy.DEVICE -> R.string.setup_busy_device
                    else -> R.string.setup_busy_testing
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (busy == Busy.BROWSER) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_busy_browser_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.ui_cancel)) }
    }
}
