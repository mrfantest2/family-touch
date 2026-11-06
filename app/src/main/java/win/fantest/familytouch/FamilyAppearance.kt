package win.fantest.familytouch

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal enum class FamilyHue(val label: String) {
    GREEN("Classic green"), PURPLE("Purple"), PINK("Pink"),
    BLUE("Blue"), RED("Red"), TEAL("Teal")
}
internal enum class FamilyDisplayMode(val label: String) {
    DARK("Dark"), LIGHT("Light"), SYSTEM("System")
}
internal data class FamilyTone(
    val background: Color, val surface: Color, val accent: Color,
    val text: Color, val secondaryText: Color, val divider: Color,
    val card: Color, val accentForeground: Color, val selected: Color,
    val control: Color, val sentBubble: Color
)

/** Persisted visual theme; changing it never alters chats, pairing keys or network state. */
internal object FamilyAppearance {
    var hue by mutableStateOf(FamilyHue.PURPLE)
        private set
    var mode by mutableStateOf(FamilyDisplayMode.DARK)
        private set
    var systemDark by mutableStateOf(true)
        private set
    private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        val p = ctx.getSharedPreferences("family_appearance_v1", Context.MODE_PRIVATE)
        hue = runCatching { FamilyHue.valueOf(p.getString("hue", "PURPLE") ?: "PURPLE") }
            .getOrDefault(FamilyHue.PURPLE)
        mode = runCatching { FamilyDisplayMode.valueOf(p.getString("mode", "DARK") ?: "DARK") }
            .getOrDefault(FamilyDisplayMode.DARK)
        systemDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    fun chooseHue(value: FamilyHue) {
        hue = value
        appContext?.getSharedPreferences("family_appearance_v1", Context.MODE_PRIVATE)
            ?.edit()?.putString("hue", value.name)?.apply()
    }
    fun chooseMode(value: FamilyDisplayMode) {
        mode = value
        appContext?.getSharedPreferences("family_appearance_v1", Context.MODE_PRIVATE)
            ?.edit()?.putString("mode", value.name)?.apply()
    }
    val dark: Boolean get() = when (mode) {
        FamilyDisplayMode.DARK -> true
        FamilyDisplayMode.LIGHT -> false
        FamilyDisplayMode.SYSTEM -> systemDark
    }
    val tone: FamilyTone get() = colors(hue, dark)

    fun colors(hue: FamilyHue, dark: Boolean): FamilyTone {
        val tint = when (hue) {
            FamilyHue.GREEN -> if (dark) Color(0xFF00A884) else Color(0xFF087862)
            FamilyHue.PURPLE -> if (dark) Color(0xFFA18AFF) else Color(0xFF6B43B8)
            FamilyHue.PINK -> if (dark) Color(0xFFFF91C7) else Color(0xFFB02E79)
            FamilyHue.BLUE -> if (dark) Color(0xFF83B9FF) else Color(0xFF2468BA)
            FamilyHue.RED -> if (dark) Color(0xFFFF8E98) else Color(0xFFB62B43)
            FamilyHue.TEAL -> if (dark) Color(0xFF6ED8D1) else Color(0xFF087D82)
        }
        if (dark) {
            if (hue == FamilyHue.GREEN) return FamilyTone(
                Color(0xFF0B141A), Color(0xFF202C33), tint, Color(0xFFE9EDEF),
                Color(0xFF8696A0), Color(0xFF304049), Color(0xFF1B292F),
                Color(0xFF092820), Color(0xFF173E37), Color(0xFF2A3942), Color(0xFF005C4B))
            return FamilyTone(
                Color(0xFF0B1223), Color(0xFF17243B), tint, Color(0xFFF6F6FF),
                Color(0xFFADB7D0), Color(0xFF293750), Color(0xFF15213A),
                Color(0xFF0B1223), Color(0xFF30264E), Color(0xFF283650),
                when (hue) {
                    FamilyHue.PURPLE -> Color(0xFF473A8F)
                    FamilyHue.PINK -> Color(0xFF78345D)
                    FamilyHue.BLUE -> Color(0xFF244875)
                    FamilyHue.RED -> Color(0xFF703444)
                    FamilyHue.TEAL -> Color(0xFF205965)
                    FamilyHue.GREEN -> Color(0xFF005C4B)
                })
        }
        return FamilyTone(
            Color(0xFFF6F7FC), Color(0xFFFFFFFF), tint, Color(0xFF192237),
            Color(0xFF58647B), Color(0xFFDDE3EF), Color(0xFFFFFFFF),
            Color.White, Color(0xFFECE7FA), Color(0xFFE9EDF5),
            when (hue) {
                FamilyHue.GREEN -> Color(0xFFD7F4E9)
                FamilyHue.PURPLE -> Color(0xFFE9E1FF)
                FamilyHue.PINK -> Color(0xFFFCE2F0)
                FamilyHue.BLUE -> Color(0xFFDEECFF)
                FamilyHue.RED -> Color(0xFFFFE1E5)
                FamilyHue.TEAL -> Color(0xFFD9F4F2)
            })
    }
}

@Composable
internal fun FamilyAppearanceSettings() {
    val selectedHue = FamilyAppearance.hue
    val selectedMode = FamilyAppearance.mode
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Choose a color and dark, light, or system mode.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FamilyDisplayMode.entries.forEach { display ->
                FilterChip(selected = display == selectedMode,
                    onClick = { FamilyAppearance.chooseMode(display) },
                    label = { Text(display.label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FamilyHue.entries.forEach { hue ->
                FilterChip(selected = hue == selectedHue,
                    onClick = { FamilyAppearance.chooseHue(hue) },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Surface(Modifier.size(14.dp), shape = CircleShape,
                                color = FamilyAppearance.colors(hue, FamilyAppearance.dark).accent) {}
                            Text(hue.label)
                        }
                    })
            }
        }
    }
}
