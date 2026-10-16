package app.plico.push.policy

import app.plico.data.PushMode

/** How an account gets new mail at this moment (DESIGN.md §8.3, push PB14). */
object PushModes {
    enum class Effective { IDLE, POLL }

    /**
     * [unmeteredWifi]: the default network is Wi-Fi and not metered. [charging]: the phone is plugged in.
     * The engine calls this again on every transport or charging change.
     */
    fun effective(mode: PushMode, unmeteredWifi: Boolean, charging: Boolean): Effective = when (mode) {
        PushMode.INSTANT -> Effective.IDLE
        PushMode.INSTANT_ON_WIFI -> if (unmeteredWifi || charging) Effective.IDLE else Effective.POLL
        PushMode.POLL -> Effective.POLL
    }
}
