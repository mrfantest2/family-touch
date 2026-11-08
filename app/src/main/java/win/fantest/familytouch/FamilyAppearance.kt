package win.fantest.familytouch

import androidx.compose.ui.graphics.Color

/** Fixed classic-green dark appearance. There is no theme selection or saved visual mode. */
internal data class FamilyTone(
    val background: Color, val surface: Color, val accent: Color,
    val text: Color, val secondaryText: Color, val divider: Color,
    val card: Color, val accentForeground: Color, val selected: Color,
    val control: Color, val sentBubble: Color
)

internal object FamilyAppearance {
    const val dark = true
    val tone = FamilyTone(
        background = Color(0xFF0B141A),
        surface = Color(0xFF202C33),
        accent = Color(0xFF00A884),
        text = Color(0xFFE9EDEF),
        secondaryText = Color(0xFF8696A0),
        divider = Color(0xFF304049),
        card = Color(0xFF1B292F),
        accentForeground = Color(0xFF092820),
        selected = Color(0xFF173E37),
        control = Color(0xFF2A3942),
        sentBubble = Color(0xFF005C4B)
    )
}
