package com.doge.ipdf

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/* ------------------------------------------------------------------ */
/* Palettes                                                            */
/* ------------------------------------------------------------------ */

private val PurpleScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceTint = Color(0xFF6750A4),
    inverseSurface = Color(0xFF322F35),
    inverseOnSurface = Color(0xFFF5EFF7),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFEF7FF),
    surfaceDim = Color(0xFFDED8E1),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainerLowest = Color.White,
)

private val LakeGreenScheme = lightColorScheme(
    primary = Color(0xFF006A67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9FF2EA),
    onPrimaryContainer = Color(0xFF00201F),
    inversePrimary = Color(0xFF83D5CE),
    secondary = Color(0xFF4A6361),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF051F1E),
    tertiary = Color(0xFF456179),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCE5FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF4FBF9),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF9),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE4E1),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceTint = Color(0xFF006A67),
    inverseSurface = Color(0xFF2B3231),
    inverseOnSurface = Color(0xFFECF2F0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF4FBF9),
    surfaceDim = Color(0xFFD6E0DD),
    surfaceContainer = Color(0xFFE8F1EE),
    surfaceContainerHigh = Color(0xFFE2EBE8),
    surfaceContainerHighest = Color(0xFFDCE6E3),
    surfaceContainerLow = Color(0xFFEEF6F3),
    surfaceContainerLowest = Color.White,
)

private val OceanScheme = lightColorScheme(
    primary = Color(0xFF0B5FA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    inversePrimary = Color(0xFFA5C8FF),
    secondary = Color(0xFF526070),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E4F7),
    onSecondaryContainer = Color(0xFF0E1D2A),
    tertiary = Color(0xFF3E6374),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC1E8FD),
    onTertiaryContainer = Color(0xFF001F2A),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFF0B5FA5),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF8F9FF),
    surfaceDim = Color(0xFFD9DAE1),
    surfaceContainer = Color(0xFFECEEF5),
    surfaceContainerHigh = Color(0xFFE6E8EF),
    surfaceContainerHighest = Color(0xFFE0E2E9),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainerLowest = Color.White,
)

private val SandScheme = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF9A),
    onPrimaryContainer = Color(0xFF261A00),
    inversePrimary = Color(0xFFF2BF48),
    secondary = Color(0xFF6A5E3F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E1B9),
    onSecondaryContainer = Color(0xFF231B04),
    tertiary = Color(0xFF4C6547),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFEBC5),
    onTertiaryContainer = Color(0xFF092010),
    background = Color(0xFFFFF9EE),
    onBackground = Color(0xFF1F1B13),
    surface = Color(0xFFFFF9EE),
    onSurface = Color(0xFF1F1B13),
    surfaceVariant = Color(0xFFEDE1CF),
    onSurfaceVariant = Color(0xFF4D4639),
    surfaceTint = Color(0xFF7A5900),
    inverseSurface = Color(0xFF343027),
    inverseOnSurface = Color(0xFFF8EFE4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7F7665),
    outlineVariant = Color(0xFFD0C5B2),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFF9EE),
    surfaceDim = Color(0xFFE1D9C9),
    surfaceContainer = Color(0xFFF5EDDD),
    surfaceContainerHigh = Color(0xFFEFE7D7),
    surfaceContainerHighest = Color(0xFFE9E1D1),
    surfaceContainerLow = Color(0xFFFBF3E4),
    surfaceContainerLowest = Color.White,
)

private val SakuraScheme = lightColorScheme(
    primary = Color(0xFF9C405E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF3F001F),
    inversePrimary = Color(0xFFFFB1C7),
    secondary = Color(0xFF74565F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF2B151C),
    tertiary = Color(0xFF7C5635),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC0),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF201A1B),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF201A1B),
    surfaceVariant = Color(0xFFF2DDE2),
    onSurfaceVariant = Color(0xFF514347),
    surfaceTint = Color(0xFF9C405E),
    inverseSurface = Color(0xFF362F30),
    inverseOnSurface = Color(0xFFFBEEEF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF857379),
    outlineVariant = Color(0xFFD7C1C7),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFF8F8),
    surfaceDim = Color(0xFFE2D8DA),
    surfaceContainer = Color(0xFFF6ECEE),
    surfaceContainerHigh = Color(0xFFF0E6E8),
    surfaceContainerHighest = Color(0xFFEAE0E2),
    surfaceContainerLow = Color(0xFFFCF2F4),
    surfaceContainerLowest = Color.White,
)

private val GraphiteScheme = lightColorScheme(
    primary = Color(0xFF465A64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCEE6F2),
    onPrimaryContainer = Color(0xFF051F28),
    inversePrimary = Color(0xFFB2CAD6),
    secondary = Color(0xFF4F6168),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E6EE),
    onSecondaryContainer = Color(0xFF0B1E24),
    tertiary = Color(0xFF5B5C7E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1E0F9),
    onTertiaryContainer = Color(0xFF181831),
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFF7F9FA),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFDDE4E7),
    onSurfaceVariant = Color(0xFF41484B),
    surfaceTint = Color(0xFF465A64),
    inverseSurface = Color(0xFF2D3133),
    inverseOnSurface = Color(0xFFEFF1F2),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF71787B),
    outlineVariant = Color(0xFFC1C8CA),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF7F9FA),
    surfaceDim = Color(0xFFD7DBDC),
    surfaceContainer = Color(0xFFEBEFF0),
    surfaceContainerHigh = Color(0xFFE5E9EA),
    surfaceContainerHighest = Color(0xFFDFE3E4),
    surfaceContainerLow = Color(0xFFF1F4F5),
    surfaceContainerLowest = Color.White,
)

enum class AppTheme(
    val id: String,
    val label: String,
    val subtitle: String,
    val scheme: androidx.compose.material3.ColorScheme,
) {
    PURPLE("purple", "Purple", "The original Material palette", PurpleScheme),
    LAKE_GREEN("lake_green", "Lake Green", "Teal-green with soft mint surfaces", LakeGreenScheme),
    OCEAN("ocean", "Ocean Blue", "Deep water blue and pale sky surfaces", OceanScheme),
    SAND("sand", "Warm Sand", "Amber and cream, low-contrast warmth", SandScheme),
    SAKURA("sakura", "Sakura", "Muted rose with soft pink surfaces", SakuraScheme),
    GRAPHITE("graphite", "Graphite", "Neutral slate for long reading sessions", GraphiteScheme),
    ;

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: LAKE_GREEN
    }
}

object AppThemeStore {
    private const val PREFS = "theme_state"
    private const val KEY = "theme_id"

    fun load(context: Context): AppTheme {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return AppTheme.fromId(id)
    }

    fun save(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, theme.id)
            .apply()
    }
}

/* ------------------------------------------------------------------ */
/* Theme picker                                                        */
/* ------------------------------------------------------------------ */

@Composable
fun ThemePickerDialog(
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.9f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    text = "Theme",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Pick a palette. It applies immediately; long-press + in the library to reopen this.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AppTheme.entries.forEach { theme ->
                        ThemeOptionRow(
                            theme = theme,
                            selected = theme == selected,
                            onClick = { onSelect(theme) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 22.dp),
                    ) {
                        Text("Done", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaletteSwatch(theme)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = theme.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = theme.subtitle,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            CheckGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PaletteSwatch(theme: AppTheme) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .width(52.dp)
            .height(34.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
    ) {
        Box(Modifier.weight(1.2f).fillMaxHeight().background(theme.scheme.primary))
        Box(Modifier.weight(0.9f).fillMaxHeight().background(theme.scheme.primaryContainer))
        Box(Modifier.weight(0.9f).fillMaxHeight().background(theme.scheme.tertiaryContainer))
    }
}
