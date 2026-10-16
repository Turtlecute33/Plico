package app.plico.ui.nav

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.plico.ui.compose.ComposeScreen
import app.plico.ui.mailbox.DraftsScreen
import app.plico.ui.mailbox.MailboxScreen
import app.plico.ui.message.MessageScreen
import app.plico.ui.settings.AboutScreen
import app.plico.ui.settings.AccountSettingsScreen
import app.plico.ui.settings.SettingsScreen
import app.plico.ui.setup.ReauthScreen
import app.plico.ui.setup.SetupScreen

/**
 * Shows the top route of [nav] with navigation3 (DESIGN §13). System back and the predictive back
 * gesture pop the stack; on the last route the system handles back. [Navigator.back] on the last
 * route finishes the activity (e.g. a share-started Compose returns to the sharing app).
 * A `viewModel()` inside a screen is scoped to its back-stack entry and cleared when the entry is popped.
 */
@Composable
fun AppNav(nav: Navigator, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    if (nav.finished) LaunchedEffect(Unit) { activity?.finish() }
    if (nav.backStack.isEmpty()) return

    // A field of the screen that leaves must not hand focus or the keyboard to the next one: after Back from
    // Compose the mailbox search field gets focus only when the user taps it.
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(nav.current) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    val forward = sharedAxis<Scene<Route>>(forward = true)
    val backward = sharedAxis<Scene<Route>>(forward = false)
    NavDisplay(
        backStack = nav.backStack,
        modifier = modifier,
        onBack = nav::back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = forward,
        popTransitionSpec = backward,
        predictivePopTransitionSpec = { backward() },
        entryProvider = entryProvider {
            entry<Route.Setup> { SetupScreen(nav) }
            entry<Route.Reauth> { ReauthScreen(it.accountId, nav) }
            entry<Route.Mailbox> { MailboxScreen(it.view, nav) }
            entry<Route.Message> { MessageScreen(it.id, nav) }
            entry<Route.Compose> { ComposeScreen(it.draftId, it.fresh, nav) }
            entry<Route.Drafts> { DraftsScreen(it.accountId, nav) }
            entry<Route.Settings> { SettingsScreen(nav) }
            entry<Route.AccountSettings> { AccountSettingsScreen(it.accountId, nav) }
            entry<Route.About> { AboutScreen(nav) }
        },
    )
}

/** A transition spec of AnimatedContent or NavDisplay. */
internal typealias Transition<S> = AnimatedContentTransitionScope<S>.() -> ContentTransform

/**
 * Material shared-axis X with the theme's expressive motion: a short slide plus a cross-fade. [forward] slides
 * towards the start edge, so the direction follows the layout direction (RTL too). Screens and setup pages.
 */
@Composable
internal fun <S> sharedAxis(forward: Boolean): Transition<S> {
    val motion = MaterialTheme.motionScheme
    val slide = motion.defaultSpatialSpec<IntOffset>()
    val fadeIn = motion.defaultEffectsSpec<Float>()
    val fadeOut = motion.fastEffectsSpec<Float>()
    val sign = if (forward == (LocalLayoutDirection.current == LayoutDirection.Ltr)) 1 else -1
    val offset = sign * with(LocalDensity.current) { 48.dp.roundToPx() }
    return {
        (slideInHorizontally(slide) { offset } + fadeIn(fadeIn)) togetherWith
            (slideOutHorizontally(slide) { -offset } + fadeOut(fadeOut))
    }
}
