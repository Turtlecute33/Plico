package app.plico.push

import androidx.annotation.StringRes
import app.plico.R

/** Grants and states that decide whether push works (DESIGN §8.2). */
data class PushStatus(
    val running: Boolean,
    val pausedByUser: Boolean,
    val stoppedBySystem: Boolean,
    val hasNetwork: Boolean,
    val grants: Grants,
)

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
