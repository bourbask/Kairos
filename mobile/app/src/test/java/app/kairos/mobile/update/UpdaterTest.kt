package app.kairos.mobile.update

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.AppRelease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdaterTest {
    private class FakeInstaller(var allowed: Boolean = true) : Installer {
        val installed = mutableListOf<Long>()
        override fun allowed() = allowed
        override suspend fun install(apk: File): UpdateOutcome {
            installed += apk.length()
            return UpdateOutcome.Installed
        }
    }

    private fun apk() = File.createTempFile("kairos", ".apk").apply { delete() }

    @Test
    fun newer_version_offered() {
        assertEquals(AppRelease("0.4.0", 10), offered(AppRelease("0.4.0", 10), "0.3.1"))
        // Comparaison numérique, pas alphabétique.
        assertTrue(newer("0.10.0", "0.9.9"))
        assertTrue(newer("1.0.0", "0.99.99"))
    }

    @Test
    fun no_update_when_server_older() {
        assertNull(offered(AppRelease("0.3.0", 10), "0.3.1"))
        assertFalse(newer("0.9.9", "0.10.0"))
        // Version illisible d'un côté ou de l'autre : rien n'est proposé.
        assertFalse(newer("0.4", "0.3.1"))
        assertFalse(newer("0.4.0", ""))
        assertNull(offered(null, "0.3.1"))
    }

    @Test
    fun same_version_no_update() {
        assertNull(offered(AppRelease("0.3.1", 10), "0.3.1"))
    }

    @Test
    fun download_failure_reports_message() = runBlocking {
        val file = apk()
        val installer = FakeInstaller()
        val out = runUpdate(AppRelease("0.4.0", 4), file, { f, _ -> f.writeBytes(byteArrayOf(1)); throw ApiError.Offline }, installer) {}
        assertTrue(out is UpdateOutcome.Failed)
        assertTrue((out as UpdateOutcome.Failed).message.contains("interrompu"))
        assertTrue(installer.installed.isEmpty())
        assertFalse("fichier partiel effacé", file.exists())
    }

    @Test
    fun incomplete_download_not_installed() = runBlocking {
        val file = apk()
        val installer = FakeInstaller()
        val out = runUpdate(AppRelease("0.4.0", 4), file, { f, _ -> f.writeBytes(byteArrayOf(1, 2)) }, installer) {}
        assertTrue(out is UpdateOutcome.Failed)
        assertTrue(installer.installed.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun complete_download_installed_then_deleted() = runBlocking {
        val file = apk()
        val installer = FakeInstaller()
        val progress = mutableListOf<Float>()
        val out = runUpdate(AppRelease("0.4.0", 4), file, { f, onBytes -> f.writeBytes(byteArrayOf(1, 2, 3, 4)); onBytes(2); onBytes(4) }, installer) { progress += it }
        assertEquals(UpdateOutcome.Installed, out)
        assertEquals(listOf(4L), installer.installed)
        assertEquals(listOf(0.5f, 1f), progress)
        assertFalse(file.exists())
    }

    @Test
    fun install_not_allowed_asks_before_download() = runBlocking {
        var downloaded = false
        val out = runUpdate(AppRelease("0.4.0", 4), apk(), { _, _ -> downloaded = true }, FakeInstaller(allowed = false)) {}
        assertEquals(UpdateOutcome.NeedsPermission, out)
        assertFalse(downloaded)
    }

    @Test
    fun check_throttled() {
        assertTrue(checkDue(null, 0))
        assertFalse(checkDue(1_000, 1_000 + CHECK_EVERY_MS - 1))
        assertTrue(checkDue(1_000, 1_000 + CHECK_EVERY_MS))
    }
}
