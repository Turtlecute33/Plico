package app.plico.push

import androidx.annotation.StringRes
import app.plico.R

/** Grants and states that decide whether push works (DESIGN §8.2). */
data class PushStatus(
    val running: Boolean,
    val pausedByUser: Boolean,
    val stoppedBySystem: Boolean,
    val hasNetwork: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val exactAlarms: Boolean,
    val backgroundRestricted: Boolean,
) {
    /** Exact alarms are possible: the alarm can also restart push after Android closed the app. */
    val reliable: Boolean get() = (batteryUnrestricted || exactAlarms) && !backgroundRestricted

    /** The one state the UI shows first (banner, "Push & battery" header). */
    val health: PushHealth
        get() = when {
            pausedByUser -> PushHealth.PAUSED
            stoppedBySystem -> PushHealth.STOPPED
            backgroundRestricted -> PushHealth.RESTRICTED
            !notificationsAllowed -> PushHealth.NOTIFICATIONS_OFF
            !hasNetwork -> PushHealth.NO_NETWORK
            !reliable -> PushHealth.UNRELIABLE
            else -> PushHealth.OK
        }
}

/** The most important push state, with its text for the app (banner, "Push & battery"). */
enum class PushHealth(@StringRes val text: Int) {
    PAUSED(R.string.push_health_paused),
    STOPPED(R.string.push_health_stopped),
    RESTRICTED(R.string.push_health_restricted),
    NOTIFICATIONS_OFF(R.string.push_health_notifications_off),
    NO_NETWORK(R.string.push_health_no_network),
    UNRELIABLE(R.string.push_health_unreliable),
    OK(R.string.push_health_ok),
}

/** Text of the foreground-service notification (DESIGN §8.1). Never an address. */
enum class ServiceText(@StringRes val text: Int) {
    CONNECTED(R.string.push_service_connected),
    WAITING_FOR_NETWORK(R.string.push_service_waiting),
    MAY_BE_LATE(R.string.push_service_late),
    ;

    companion object {
        /** [delayed]: a session waits for a retry or has a problem. */
        fun of(hasNetwork: Boolean, reliable: Boolean, delayed: Boolean): ServiceText = when {
            !hasNetwork -> WAITING_FOR_NETWORK
            !reliable || delayed -> MAY_BE_LATE
            else -> CONNECTED
        }
    }
}
