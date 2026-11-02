package win.fantest.familytouch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUpdateUrlPolicyTest {
    @Test fun allowsSignedManifestSources() {
        assertTrue(FamilyUpdates.isAllowedDownloadUrl(
            "https://family-updates.fantest.win/apks/embedded.apk"))
        assertTrue(FamilyUpdates.isAllowedDownloadUrl(
            "https://family-updates.fantest.win/apks/drive/embedded-beta-v35.apk"))
        assertTrue(FamilyUpdates.isAllowedDownloadUrl(
            "https://drive.google.com/uc?export=download&id=example"))
        assertTrue(FamilyUpdates.isAllowedDownloadUrl(
            "https://drive.usercontent.google.com/download?id=example&export=download"))
    }
    @Test fun rejectsUntrustedDownloadHostsAndProtocols() {
        val bad = listOf(
            "http://family-updates.fantest.win/apks/embedded.apk",
            "https://family-updates.fantest.win.evil.example/apks/embedded.apk",
            "https://family-updates.fantest.win/health",
            "https://attacker.googleusercontent.example/download",
            "https://notdrive.google.com/uc?id=123",
            "file:///sdcard/Download/embedded.apk",
            "https://example.com/embedded.apk"
        )
        bad.forEach { assertFalse(it, FamilyUpdates.isAllowedDownloadUrl(it)) }
    }
    @Test fun stableUniqueWorkKeyPerVersion() {
        assertTrue(FamilyUpdates.workName(35L) == FamilyUpdates.workName(35L))
        assertFalse(FamilyUpdates.workName(35L) == FamilyUpdates.workName(36L))
    }
}
