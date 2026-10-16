package app.plico.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Vault, Prefs and Files on the device. */
class PlatformTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearPrefs() {
        context.getSharedPreferences("plico", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun vaultRoundTripsWithFreshIvs() {
        val vault = Vault()
        val a = vault.encryptString("app password")
        val b = vault.encryptString("app password")
        assertEquals(12 + 12 + 16, a.size)
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
        assertEquals("app password", vault.decryptString(a))
        // A second instance uses the same keystore key.
        assertEquals("app password", Vault().decryptString(b))
        assertContentEquals(ByteArray(0), vault.decrypt(vault.encrypt(ByteArray(0))))

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(store.containsAlias("plico.vault.v1"))
    }

    @Test
    fun vaultRejectsTamperedOrShortBlobs() {
        val vault = Vault()
        val blob = vault.encryptString("secret")
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertFailsWith<AEADBadTagException> { vault.decrypt(blob) }
        assertFailsWith<GeneralSecurityException> { vault.decrypt(ByteArray(20)) }
        assertFailsWith<GeneralSecurityException> { vault.decryptString(ByteArray(0)) }
    }

    @Test
    fun prefsDefaultsAndWrites() {
        val prefs = Prefs(context)
        assertEquals(Prefs.Swipe.ARCHIVE, prefs.swipeStart)
        assertEquals(Prefs.Swipe.DELETE, prefs.swipeEnd)
        assertEquals(5, prefs.undoSendSeconds)
        assertEquals(
            listOf(false, true, true, false, true, false, true, false, false, false, false),
            listOf(
                prefs.remoteContentDefault, prefs.confirmLinks, prefs.darkEmail, prefs.appLock, prefs.hideInRecents,
                prefs.blockScreenshots, prefs.incognitoKeyboard, prefs.hideNotificationContent, prefs.pushPaused,
                prefs.pushStoppedBySystemUi, prefs.gmailIpNoteShown,
            ),
        )
        assertEquals(0, prefs.appLockTimeoutMinutes)
        assertEquals(app.plico.BuildConfig.GOOGLE_CLIENT_ID, prefs.googleClientId) // debug build preset

        prefs.swipeEnd = Prefs.Swipe.STAR
        prefs.undoSendSeconds = 0
        prefs.appLock = true
        prefs.googleClientId = "  id.apps.googleusercontent.com "
        val again = Prefs(context)
        assertEquals(Prefs.Swipe.STAR, again.swipeEnd)
        assertEquals(0, again.undoSendSeconds)
        assertTrue(again.appLock)
        assertEquals("id.apps.googleusercontent.com", again.googleClientId)
        again.googleClientId = ""
        assertEquals("", again.googleClientId)

        prefs.put("keepalive", "{}")
        assertEquals("{}", prefs.get("keepalive"))
        prefs.put("keepalive", null)
        assertEquals(null, prefs.get("keepalive"))
    }

    @Test
    fun prefsChangesEmitNowAndOnWrite(): Unit = runBlocking {
        val prefs = Prefs(context)
        val events = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Main) { prefs.changes().collect { events.send(it) } }
        withTimeout(5_000) { events.receive() }
        prefs.darkEmail = false
        withTimeout(5_000) { events.receive() }
        job.cancel()
    }

    @Test
    fun filesPathsAndOutbox() {
        val files = Files(context)
        val att = files.attachment(42, 7)
        assertEquals(File(context.cacheDir.canonicalFile, "att/42/7"), att)
        assertTrue(att.parentFile!!.isDirectory)

        val dir = files.outboxDir(9)
        assertEquals(File(context.filesDir.canonicalFile, "outbox/9"), dir)
        val first = files.newOutboxFile(9, "../../shared_prefs/plico.xml")
        val second = files.newOutboxFile(9, "../../shared_prefs/plico.xml")
        val third = files.newOutboxFile(9, "\u202Egpj.exe")
        assertEquals(listOf("_.._shared_prefs_plico.xml", "2-_.._shared_prefs_plico.xml", "_gpj.exe"), listOf(first, second, third).map { it.name })
        assertTrue(listOf(first, second, third).all { it.parentFile == dir && it.exists() })
        assertNotEquals(first, second)

        // The built message has a fixed name that no attachment can take.
        val eml = files.outboxMessage(9)
        assertEquals(File(dir, "message.eml"), eml)
        assertEquals("2-message.eml", files.newOutboxFile(9, "message.eml").name)
        assertEquals(File(context.cacheDir.canonicalFile, "att/42/7.part"), files.attachmentPart(42, 7))

        files.deleteOutbox(9)
        assertFalse(dir.exists())
        files.deleteAttachments(42)
        assertFalse(att.parentFile!!.exists())
    }

    @Test
    fun pruneDeletesOnlyOldCacheFiles() {
        val files = Files(context)
        val old = files.attachment(500, 1).apply { writeText("old") }
        val fresh = files.attachment(501, 1).apply { writeText("new") }
        val hour = 60 * 60 * 1000L
        old.setLastModified(System.currentTimeMillis() - 2 * hour)
        old.parentFile!!.setLastModified(System.currentTimeMillis() - 2 * hour)

        // An old dir that still holds a fresh file (a download in progress) stays.
        val busy = files.attachmentPart(502, 1).apply { writeText("part") }
        busy.parentFile!!.setLastModified(System.currentTimeMillis() - 2 * hour)

        files.pruneAttachmentCache()
        assertFalse(old.exists())
        assertFalse(old.parentFile!!.exists())
        assertTrue(fresh.exists())
        assertTrue(busy.exists())
        files.deleteAttachments(501)
        files.deleteAttachments(502)
    }
}
