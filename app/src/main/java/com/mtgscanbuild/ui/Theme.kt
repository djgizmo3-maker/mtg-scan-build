package com.mtgscanbuild.ui

import android.app.Activity
import android.app.Application
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtgscanbuild.MtgApp
import com.mtgscanbuild.data.Accent
import com.mtgscanbuild.data.AppSettings
import com.mtgscanbuild.data.Repository
import com.mtgscanbuild.data.ThemeMode
import com.mtgscanbuild.scan.ScanSounds

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val settings = (LocalContext.current.applicationContext as Application).settings
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val accent = settings.accent
    val ctx = LocalContext.current
    val scheme = when {
        accent == Accent.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Color(accent.dark).let { p ->
            darkColorScheme(
                primary = p,
                onPrimary = Color(0xFF1B1424),
                primaryContainer = lerp(p, Color(0xFF1B1424), 0.6f),
                onPrimaryContainer = lerp(p, Color.White, 0.6f),
                secondary = Color(0xFFFFB74D),
                secondaryContainer = lerp(p, Color(0xFF1B1424), 0.7f),
                background = Color(0xFF1B1424),
                surface = Color(0xFF1B1424),
                surfaceVariant = Color(0xFF2D2438),
            )
        }
        else -> Color(accent.light).let { p ->
            lightColorScheme(
                primary = p,
                onPrimary = Color.White,
                primaryContainer = lerp(p, Color.White, 0.75f),
                onPrimaryContainer = lerp(p, Color.Black, 0.5f),
                secondary = Color(0xFFB26A00),
                secondaryContainer = lerp(p, Color.White, 0.8f),
                background = Color(0xFFFBF8FD),
                surface = Color(0xFFFBF8FD),
                surfaceVariant = Color(0xFFEAE3F0),
            )
        }
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val bg = scheme.background.toArgb()
        window.decorView.setBackgroundColor(bg)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = bg; window.navigationBarColor = bg }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

val Application.repo: Repository get() = (this as MtgApp).repo
val Application.settings: AppSettings get() = (this as MtgApp).settings
val Application.sounds: ScanSounds get() = (this as MtgApp).sounds

fun manaColor(c: Char): Color = when (c) {
    'W' -> Color(0xFFF8F1D8)
    'U' -> Color(0xFF6EB3E8)
    'B' -> Color(0xFF8E7F86)
    'R' -> Color(0xFFE8705A)
    'G' -> Color(0xFF5FB27A)
    else -> Color(0xFFB0B0B0)
}

/** Small colored pips, e.g. for "WU". Empty string shows a colorless pip. */
@Composable
fun ColorPips(colors: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val list = colors.ifEmpty { "C" }
        list.forEach { c ->
            Box(
                Modifier.padding(end = 3.dp).size(18.dp).background(manaColor(c), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text(c.toString(), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
