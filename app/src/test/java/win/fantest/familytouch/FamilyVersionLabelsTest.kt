package win.fantest.familytouch

import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyVersionLabelsTest {
    @Test fun showsShortReadableVersionWithBuildNumber() {
        assertEquals("v0.3.21 (build 38)",
            FamilyVersionLabels.format("0.3.21-version-status-embedded", 38))
        assertEquals("v0.3.16 (build 33)",
            FamilyVersionLabels.format("0.3.16-updater-verified-embedded", 33))
    }

    @Test fun distinguishesOlderServerFromMatchingAndNewerRelease() {
        assertEquals("Your installed version is newer than the published update feed.",
            FamilyVersionLabels.status(38, 33))
        assertEquals("Your version matches the latest published release.",
            FamilyVersionLabels.status(38, 38))
        assertEquals("A newer signed update is available.",
            FamilyVersionLabels.status(38, 39))
    }
}
