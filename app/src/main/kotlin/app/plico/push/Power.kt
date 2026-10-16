package app.plico.push

import android.content.Context
import app.plico.sync.WakeHolder

/** Partial wake locks, one non-refcounted lock per holder (DESIGN §8.3). Owner: push agent. */
class Power(private val context: Context) {
    /** [tag] like "sync:3"; never an address. */
    fun holder(tag: String): WakeHolder = TODO()
}
