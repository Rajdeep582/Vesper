package com.rajdeep.vesper.ui.theme

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.rajdeep.vesper.R
import com.rajdeep.vesper.data.Prefs
import com.rajdeep.vesper.data.ThemeMode

// ---------------------------------------------------------------- palette ("Dusk")

@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    val hairline: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val hall: Color,
    val direct: Color,
    val console: Color,
    val error: Color,
    val success: Color,
    val onAccent: Color,
    val scrim: Color,
)

val DuskDark = Palette(
    dark = true,
    bg = Color(0xFF0D0E12), s1 = Color(0xFF15171D), s2 = Color(0xFF1C1F27), s3 = Color(0xFF242833),
    hairline = Color(0xFF2A2E38), text = Color(0xFFECE8E1), text2 = Color(0xFF9A968E), text3 = Color(0xFF6B6862),
    hall = Color(0xFFE2B36B), direct = Color(0xFF8FA3C8), console = Color(0xFF6FB5A6),
    error = Color(0xFFE06C6C), success = Color(0xFF7FB89A), onAccent = Color(0xFF14120E), scrim = Color(0xCC07080A),
)

val DuskLight = Palette(
    dark = false,
    bg = Color(0xFFF6F3EE), s1 = Color(0xFFEFEBE4), s2 = Color(0xFFE7E2D9), s3 = Color(0xFFDDD7CC),
    hairline = Color(0xFFD5CFC4), text = Color(0xFF1B1A18), text2 = Color(0xFF5E5A53), text3 = Color(0xFF8A857C),
    hall = Color(0xFF9A6A1E), direct = Color(0xFF3F5A8C), console = Color(0xFF2F7A6B),
    error = Color(0xFFB3403F), success = Color(0xFF3D7A5A), onAccent = Color(0xFFFFFBF4), scrim = Color(0x99201E1A),
)

/** Which space the user is in. The accent always answers "public, private or admin?". */
enum class Space { HALL, DIRECT, CONSOLE, NEUTRAL }

fun Palette.accent(space: Space) = when (space) {
    Space.HALL -> hall; Space.DIRECT -> direct; Space.CONSOLE -> console; Space.NEUTRAL -> text
}

// ---------------------------------------------------------------- typography

val Fraunces = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_medium, FontWeight.Medium),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
)
val Instrument = FontFamily(
    Font(R.font.instrument_regular, FontWeight.Normal),
    Font(R.font.instrument_medium, FontWeight.Medium),
    Font(R.font.instrument_semibold, FontWeight.SemiBold),
)
val Mono = FontFamily(Font(R.font.jetbrains_regular, FontWeight.Normal), Font(R.font.jetbrains_medium, FontWeight.Medium))

@Immutable
data class TypeScale(
    val display: TextStyle = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp),
    val title: TextStyle = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    val heading: TextStyle = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    /** Usernames on profile headers: UI sans so names read the same everywhere; Fraunces stays for screen titles. */
    val name: TextStyle = TextStyle(fontFamily = Instrument, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    val subhead: TextStyle = TextStyle(fontFamily = Instrument, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    val body: TextStyle = TextStyle(fontFamily = Instrument, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    val bodySmall: TextStyle = TextStyle(fontFamily = Instrument, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    val label: TextStyle = TextStyle(fontFamily = Instrument, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    val caption: TextStyle = TextStyle(fontFamily = Instrument, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
    val mono: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    val metric: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
)

// ---------------------------------------------------------------- motion tokens

object Motion {
    const val XS = 90
    const val S = 180
    const val M = 280
    const val L = 420
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    fun <T> spring() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    fun <T> tw(ms: Int = M) = tween<T>(ms, easing = Emphasized)
    val offsetSpring = spring<IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
}

// ---------------------------------------------------------------- feedback (haptics + sound)

class Feedback(private val view: View, private val sounds: Sounds?, private val prefs: Prefs) {
    private fun h(c: Int) { if (prefs.haptics) view.performHapticFeedback(c) }
    fun send() { h(HapticFeedbackConstants.CONFIRM); if (prefs.sounds) sounds?.play(Sounds.SEND) }
    fun receive() { if (prefs.sounds) sounds?.play(Sounds.RECEIVE) }
    fun tick() = h(HapticFeedbackConstants.CLOCK_TICK)
    fun press() = h(HapticFeedbackConstants.VIRTUAL_KEY)
    fun longPress() = h(HapticFeedbackConstants.LONG_PRESS)
    fun recordStart() { h(HapticFeedbackConstants.LONG_PRESS); if (prefs.sounds) sounds?.play(Sounds.REC_START) }
    fun recordStop() { h(HapticFeedbackConstants.CONFIRM); if (prefs.sounds) sounds?.play(Sounds.REC_STOP) }
    fun error() { h(HapticFeedbackConstants.REJECT); if (prefs.sounds) sounds?.play(Sounds.ERROR) }
}

/** Short original UI sounds via SoundPool; silent when the ringer is silent/vibrate. */
class Sounds(ctx: android.content.Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder().setMaxStreams(3)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = mapOf(
        SEND to pool.load(ctx, R.raw.snd_send, 1), RECEIVE to pool.load(ctx, R.raw.snd_receive, 1),
        REC_START to pool.load(ctx, R.raw.snd_record_start, 1), REC_STOP to pool.load(ctx, R.raw.snd_record_stop, 1),
        ERROR to pool.load(ctx, R.raw.snd_error, 1),
    )

    fun play(which: Int) {
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        ids[which]?.let { pool.play(it, 0.35f, 0.35f, 1, 0, 1f) }
    }

    companion object { const val SEND = 0; const val RECEIVE = 1; const val REC_START = 2; const val REC_STOP = 3; const val ERROR = 4 }
}

// ---------------------------------------------------------------- locals

val LocalPalette = staticCompositionLocalOf { DuskDark }
val LocalType = staticCompositionLocalOf { TypeScale() }
val LocalSpace = compositionLocalOf { Space.NEUTRAL }
val LocalFeedback = staticCompositionLocalOf<Feedback> { error("no feedback") }
val LocalReduceMotion = staticCompositionLocalOf { false }

object V {
    val c: Palette @Composable get() = LocalPalette.current
    val t: TypeScale @Composable get() = LocalType.current
    val accent: Color @Composable get() = LocalPalette.current.accent(LocalSpace.current)
    val fb: Feedback @Composable get() = LocalFeedback.current
}

@Composable
fun VesperTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val dark = when (prefs.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val p = if (dark) DuskDark else DuskLight
    val ctx = LocalContext.current
    val view = LocalView.current
    val sounds = remember { runCatching { Sounds(ctx.applicationContext) }.getOrNull() }
    val fb = remember(prefs, view) { Feedback(view, sounds, prefs) }
    val scheme = if (dark) darkColorScheme(
        primary = p.hall, onPrimary = p.onAccent, background = p.bg, onBackground = p.text, surface = p.s1, onSurface = p.text,
        surfaceVariant = p.s2, onSurfaceVariant = p.text2, outline = p.hairline, error = p.error, surfaceContainer = p.s2,
        surfaceContainerHigh = p.s3, surfaceContainerLow = p.s1,
    ) else lightColorScheme(
        primary = p.hall, onPrimary = p.onAccent, background = p.bg, onBackground = p.text, surface = p.s1, onSurface = p.text,
        surfaceVariant = p.s2, onSurfaceVariant = p.text2, outline = p.hairline, error = p.error, surfaceContainer = p.s2,
        surfaceContainerHigh = p.s3, surfaceContainerLow = p.s1,
    )
    CompositionLocalProvider(
        LocalPalette provides p, LocalType provides TypeScale(), LocalFeedback provides fb, LocalReduceMotion provides prefs.reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
