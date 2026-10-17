package app.plico.push.policy

import app.plico.data.PushMode

/** How an account gets new mail at this moment (DESIGN.md §8.3, push PB14). */
object PushModes {
    enum class Effective { IDLE, POLL }

    /**
     * [idle]: the server has IDLE (`account.idle`); without it the account always polls, and the user's mode is kept
     * for a later server change. [unmeteredWifi]: the default network is Wi-Fi and not metered. [charging]: the phone
     * is plugged in. The engine calls this again on every transport or charging change.
     */
    fun effective(mode: PushMode, idle: Boolean, unmeteredWifi: Boolean, charging: Boolean): Effective = when {
        !idle -> Effective.POLL
        mode == PushMode.INSTANT -> Effective.IDLE
        mode == PushMode.INSTANT_ON_WIFI -> if (unmeteredWifi || charging) Effective.IDLE else Effective.POLL
        else -> Effective.POLL
    }
}
