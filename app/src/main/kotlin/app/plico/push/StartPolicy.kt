package app.plico.push

/** Whether a [PushService] start may run push (DESIGN §8.1; push PB6, apis AF1). Pure. */
internal object StartPolicy {
    enum class Source {
        /** The app UI in the foreground. */
        UI,

        /** BOOT_COMPLETED; it has already cleared a Task Manager stop. */
        BOOT,

        /** The "Resume" action of the "Push stopped" notification. */
        RESUME,

        /** Archive / Delete / Mark read on a new-mail notification. */
        ACTION,

        /** Alarm, app update, exact-alarm grant, or a sticky restart by the system. */
        BACKGROUND,
    }

    enum class Decision {
        RUN,

        /** Paused in the app, or stopped from the Task Manager earlier: stop quietly. */
        STOP,

        /** The user closed the app from the Task Manager: remember it, cancel the alarm, tell the user. */
        STOPPED_BY_USER,
    }

    /**
     * [firstStart]: the first start in this process; only then does the last process exit tell anything
     * about this start. [lastExitUserRequested] reads `ApplicationExitInfo.REASON_USER_REQUESTED`.
     */
    fun decide(
        source: Source,
        pausedByUser: Boolean,
        stoppedBySystemUi: Boolean,
        firstStart: Boolean,
        lastExitUserRequested: () -> Boolean,
    ): Decision = when {
        pausedByUser || stoppedBySystemUi -> Decision.STOP
        firstStart && source == Source.BACKGROUND && lastExitUserRequested() -> Decision.STOPPED_BY_USER
        else -> Decision.RUN
    }
}
