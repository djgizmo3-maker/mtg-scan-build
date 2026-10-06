package com.mtgscanbuild.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtgscanbuild.MtgApp
import com.mtgscanbuild.data.Repository

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB39DDB),
            secondary = Color(0xFFFFB74D),
            background = Color(0xFF1B1424),
            surface = Color(0xFF1B1424),
            surfaceVariant = Color(0xFF2D2438),
        ),
        content = content,
    )
}

val Application.repo: Repository get() = (this as MtgApp).repo

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
