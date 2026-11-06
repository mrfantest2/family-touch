package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test

class FamilyAppearanceTest {
    @Test fun allColorsDifferent() {
        val a = FamilyHue.entries.map { FamilyAppearance.colors(it, true).accent }
        assertEquals(FamilyHue.entries.size, a.toSet().size)
    }
    @Test fun greenRestoresOriginalPalette() {
        val green = FamilyAppearance.colors(FamilyHue.GREEN, true)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00A884), green.accent)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF0B141A), green.background)
    }
    @Test fun eachLightModeContrastsWithDark() {
        FamilyHue.entries.forEach {
            val dark=FamilyAppearance.colors(it, true)
            val light=FamilyAppearance.colors(it, false)
            assertNotEquals(dark.background, light.background)
            assertNotEquals(dark.text, light.text)
        }
    }
}
