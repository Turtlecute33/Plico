package app.plico.push.policy

/** What a session does on a default-network callback, after the 2 s debounce (DESIGN.md §8.3 Network, push PB5). */
object NetworkRules {
    enum class Event {
        /** A new default network. */
        DEFAULT_CHANGED,

        /** The default network gained VALIDATED or was unblocked. */
        VALIDATED,

        /** No default network. */
        LOST,
    }

    enum class Action {
        NONE,

        /** Close the socket (if any) and connect on the default network; reset the backoff once per network. */
        RECONNECT,

        /** Close the socket and wait for a network, without a backoff step. */
        PARK,
    }

    /**
     * [sessionNetwork]: network handle of the session's socket, null when not connected. [wantsConnection]: the
     * account is in IDLE mode now, or it has a poll or retry waiting.
     */
    fun action(event: Event, sessionNetwork: Long?, defaultNetwork: Long?, wantsConnection: Boolean): Action =
        when (event) {
            Event.LOST -> Action.PARK
            Event.DEFAULT_CHANGED -> when {
                sessionNetwork != null && sessionNetwork != defaultNetwork -> Action.RECONNECT
                sessionNetwork == null && wantsConnection -> Action.RECONNECT
                else -> Action.NONE
            }
            Event.VALIDATED -> if (sessionNetwork == null && wantsConnection) Action.RECONNECT else Action.NONE
        }
}
