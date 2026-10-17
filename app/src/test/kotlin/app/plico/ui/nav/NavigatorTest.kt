package app.plico.ui.nav

import app.plico.data.MailView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val inbox = Route.Mailbox(MailView.Unified)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun nav(vararg routes: Route) = Navigator().apply { routes.forEach(::go) }

    @Test
    fun startsEmpty() {
        val nav = Navigator()
        assertTrue(nav.backStack.isEmpty())
        assertEquals(null, nav.current)
    }

    @Test
    fun startWithRunsOnce() = runTest(dispatcher) {
        val nav = Navigator()
        val first = CompletableDeferred<Route>()
        nav.startWith { first.await() }
        nav.startWith { Route.Setup }
        advanceUntilIdle()
        assertTrue(nav.backStack.isEmpty())
        first.complete(inbox)
        advanceUntilIdle()
        assertEquals(listOf<Route>(inbox), nav.backStack)
        nav.startWith { Route.Setup }
        advanceUntilIdle()
        assertEquals(listOf<Route>(inbox), nav.backStack)
    }

    @Test
    fun goPushes() {
        val nav = nav(inbox, Route.Message(1))
        assertEquals(listOf(inbox, Route.Message(1)), nav.backStack)
        assertEquals(Route.Message(1), nav.current)
    }

    @Test
    fun goToAScreenInTheStackReturnsToIt() {
        val nav = nav(inbox, Route.Message(1), Route.Compose(2))
        nav.go(inbox)
        assertEquals(listOf<Route>(inbox), nav.backStack)
    }

    @Test
    fun goToTheTopScreenChangesNothing() {
        val nav = nav(inbox, Route.Message(1))
        nav.go(Route.Message(1))
        assertEquals(listOf(inbox, Route.Message(1)), nav.backStack)
    }

    @Test
    fun backPops() {
        val nav = nav(inbox, Route.Settings, Route.About)
        nav.back()
        assertEquals(listOf(inbox, Route.Settings), nav.backStack)
        assertFalse(nav.finished)
    }

    @Test
    fun backOnTheLastScreenFinishes() {
        val nav = nav(Route.Compose(3))
        nav.back()
        assertEquals(listOf<Route>(Route.Compose(3)), nav.backStack)
        assertTrue(nav.finished)
    }

    @Test
    fun resetLeavesOneScreen() {
        val nav = nav(Route.Setup, Route.Settings)
        nav.reset(inbox)
        assertEquals(listOf<Route>(inbox), nav.backStack)
    }

    @Test
    fun replaceTopSwapsTheTop() {
        val nav = nav(inbox, Route.Mailbox(MailView.InFolder(4)))
        nav.replaceTop(Route.Mailbox(MailView.Starred(null)))
        assertEquals(listOf(inbox, Route.Mailbox(MailView.Starred(null))), nav.backStack)
    }

    @Test
    fun replaceTopKeepsRoutesUnique() {
        val nav = nav(inbox, Route.Mailbox(MailView.InFolder(4)))
        nav.replaceTop(inbox)
        assertEquals(listOf<Route>(inbox), nav.backStack)
    }

    @Test
    fun replaceTopOnAnEmptyStackAdds() {
        val nav = Navigator()
        nav.replaceTop(Route.Setup)
        assertEquals(listOf<Route>(Route.Setup), nav.backStack)
    }

    @Test
    fun serverSettingsAndSignInAgainAreTwoScreens() {
        assertEquals(Route.Reauth(5), Route.Reauth(5, editServers = false))
        val nav = nav(inbox, Route.Settings, Route.AccountSettings(5), Route.Reauth(5, editServers = true))
        assertEquals(Route.Reauth(5, editServers = true), nav.current)
        nav.go(Route.Reauth(5))
        assertEquals(
            listOf(inbox, Route.Settings, Route.AccountSettings(5), Route.Reauth(5, editServers = true), Route.Reauth(5)),
            nav.backStack,
        )
        nav.back()
        nav.back()
        assertEquals(Route.AccountSettings(5), nav.current)
    }
}
