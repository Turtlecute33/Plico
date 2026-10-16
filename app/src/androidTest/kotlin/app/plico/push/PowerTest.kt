package app.plico.push

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Wake locks on the device: one non-refcounted lock per holder. */
class PowerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val power = Power(context)

    @Test
    fun renewExtendsAndOneReleaseEnds() {
        val holder = power.holder("test:renew") as Power.Holder
        assertSame(holder, power.holder("test:renew"))
        assertFalse(holder.isHeld)
        holder.renew(5_000)
        holder.renew(5_000)
        assertTrue(holder.isHeld)
        holder.release()
        assertFalse(holder.isHeld)
        // Idempotent and never throws.
        holder.release()
        assertFalse(holder.isHeld)
    }

    @Test
    fun timeoutReleases() {
        val holder = power.holder("test:timeout") as Power.Holder
        holder.renew(200)
        assertTrue(holder.isHeld)
        val until = System.currentTimeMillis() + 3_000
        while (holder.isHeld && System.currentTimeMillis() < until) Thread.sleep(50)
        assertFalse(holder.isHeld)
        holder.release()
    }
}
