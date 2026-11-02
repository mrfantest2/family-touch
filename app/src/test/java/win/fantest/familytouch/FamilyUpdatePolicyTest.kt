package win.fantest.familytouch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyUpdatePolicyTest {
    @Test fun allowsOnlyNamedHttpsUpdateHosts() {
        assertTrue(FamilyUpdates.isAllowedDownloadUrl("https://family-updates.fantest.win/apks/embedded.apk"))
        assertTrue(FamilyUpdates.isAllowedDownloadUrl("https://drive.google.com/uc?export=download&id=abc"))
        assertTrue(FamilyUpdates.isAllowedDownloadUrl("https://drive.usercontent.google.com/download?id=abc"))
        assertFalse(FamilyUpdates.isAllowedDownloadUrl("http://family-updates.fantest.win/apks/embedded.apk"))
        assertFalse(FamilyUpdates.isAllowedDownloadUrl("https://family-updates.fantest.win.invalid/apks/embedded.apk"))
        assertFalse(FamilyUpdates.isAllowedDownloadUrl("https://drive.google.com.evil.net/uc?id=x"))
        assertFalse(FamilyUpdates.isAllowedDownloadUrl("https://user@drive.google.com/uc?id=x"))
        assertFalse(FamilyUpdates.isAllowedDownloadUrl("https://evil.example.com/apks/embedded.apk"))
    }
}