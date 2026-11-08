package win.fantest.familytouch

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ClassicDarkAppearanceTest {
    @Test fun classicGreenAndDarkAreFixed() {
        assertTrue(FamilyAppearance.dark)
        assertEquals(Color(0xFF00A884), FamilyAppearance.tone.accent)
        assertEquals(Color(0xFF0B141A), FamilyAppearance.tone.background)
        assertEquals(Color(0xFF202C33), FamilyAppearance.tone.surface)
        assertEquals(Color(0xFF005C4B), FamilyAppearance.tone.sentBubble)
    }
}
