package pl.sinusoide.sinuschat.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.sinusoide.sinuschat.util.BackupManager
import java.util.TimeZone

class ExternalBackupTest {

    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"

    @Test
    fun exportActionIsRecognised() {
        assertTrue(ExternalBackup.isExportRequest("pl.sinusoide.sinuschat.action.EXPORT_BACKUP"))
        assertFalse(ExternalBackup.isExportRequest(view))
        assertFalse(ExternalBackup.isExportRequest(null))
        assertFalse(ExternalBackup.isExportRequest("pl.sinusoide.sinuschat.action.export_backup"))
    }

    @Test
    fun viewUsesData() {
        val uri = "content://pl.etg.files/backups/SinusChat-2026-11-23-1200.sinuschat"
        assertEquals(uri, ExternalBackup.importSource(view, uri, null))
        assertEquals(uri, ExternalBackup.importSource(view, uri, "content://other/x"))
    }

    @Test
    fun sendPrefersStream() {
        val stream = "content://pl.etg.files/a.sinuschat"
        assertEquals(stream, ExternalBackup.importSource(send, "content://x/y", stream))
        assertEquals("content://x/y", ExternalBackup.importSource(send, "content://x/y", null))
    }

    @Test
    fun onlyContentUrisAreAccepted() {
        assertNull(ExternalBackup.importSource(view, "file:///sdcard/Download/a.sinuschat", null))
        assertNull(ExternalBackup.importSource(view, "https://example.com/a.sinuschat", null))
        assertNull(ExternalBackup.importSource(send, null, "file:///data/data/x/a.sinuschat"))
        assertNull(ExternalBackup.importSource(view, "a.sinuschat", null))
        assertEquals("CONTENT://x/a", ExternalBackup.importSource(view, "CONTENT://x/a", null))
    }

    @Test
    fun missingOrUnknownInputIsIgnored() {
        assertNull(ExternalBackup.importSource(view, null, null))
        assertNull(ExternalBackup.importSource(send, null, null))
        assertNull(ExternalBackup.importSource(null, "content://x/a", null))
        assertNull(ExternalBackup.importSource("android.intent.action.SEND_MULTIPLE", null, "content://x/a"))
    }

    @Test
    fun backupNames() {
        assertTrue(ExternalBackup.isBackupName("SinusChat-2026-11-23-1200.sinuschat"))
        assertTrue(ExternalBackup.isBackupName("kopia.SINUSCHAT "))
        assertFalse(ExternalBackup.isBackupName("kopia.sinuschat.zip"))
        assertFalse(ExternalBackup.isBackupName("sinuschat"))
        assertFalse(ExternalBackup.isBackupName(null))
    }

    @Test
    fun hexIsLowercaseAndPadded() {
        assertEquals("00010aff7f80", ExternalBackup.hex(byteArrayOf(0, 1, 10, -1, 127, -128)))
        assertEquals("", ExternalBackup.hex(ByteArray(0)))
    }

    @Test
    fun callerFromReferrer() {
        assertEquals("pl.sinusoide.etg", ExternalBackup.callerFromReferrer("android-app://pl.sinusoide.etg"))
        assertEquals("pl.sinusoide.etg", ExternalBackup.callerFromReferrer("android-app://pl.sinusoide.etg/https/x"))
        assertNull(ExternalBackup.callerFromReferrer("https://example.com"))
        assertNull(ExternalBackup.callerFromReferrer("android-app://"))
        assertNull(ExternalBackup.callerFromReferrer(null))
    }

    @Test
    fun exportFileName() {
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val name = BackupManager.fileName(1795435500000L)
            assertEquals("SinusChat-2026-11-23-1205.sinuschat", name)
            assertTrue(ExternalBackup.isBackupName(name))
        } finally {
            TimeZone.setDefault(tz)
        }
    }
}
