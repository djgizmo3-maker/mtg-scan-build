package com.mtgscanbuild.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.mtgscanbuild.data.ManaLand
import kotlin.math.max

internal const val MANA_WALLPAPER_OPACITY = 0.18f

/** Original landscape illustrations, rendered locally without card art or network requests. */
@Composable
fun ManaWallpaper(modifier: Modifier = Modifier) {
    val accent = (LocalContext.current.applicationContext as Application).settings.accent
    val tint = MaterialTheme.colorScheme.primary
    Canvas(modifier.testTag("mana-wallpaper-${accent.land.name}")
        .graphicsLayer { alpha = MANA_WALLPAPER_OPACITY }) {
        // Center-crop the same portrait composition on phones, tablets and in landscape.
        val zoom = max(size.width / 600f, size.height / 1000f)
        withTransform({
            translate((size.width - 600f * zoom) / 2f, (size.height - 1000f * zoom) / 2f)
            scale(zoom, zoom, pivot = Offset.Zero)
        }) {
            landscape(accent.land, tint)
        }
    }
}

private fun DrawScope.landscape(land: ManaLand, tint: Color) {
    val sky = when (land) {
        ManaLand.PLAINS -> Color(0xFFECCB80)
        ManaLand.ISLAND -> Color(0xFF75C4DD)
        ManaLand.SWAMP -> Color(0xFF8D829D)
        ManaLand.MOUNTAIN -> Color(0xFFD98869)
        ManaLand.FOREST -> Color(0xFF83B9A0)
        ManaLand.WASTES -> tint
    }
    drawRect(Brush.verticalGradient(listOf(sky, Color(0xFF293F56)), endY = 1000f),
        size = Size(600f, 1000f))
    drawCircle(Color(0xFFFFEBC1), if (land == ManaLand.SWAMP) 52f else 72f, Offset(435f, 245f))
    repeat(5) { n ->
        drawOval(Color.White.copy(alpha = 0.16f),
            Offset(n * 145f - 140f, 270f + n % 2 * 65f), Size(250f, 30f))
    }
    when (land) {
        ManaLand.PLAINS -> {
            shape(Color(0xFF8F9C64), 0f, 470f, 130f, 410f, 290f, 450f, 440f, 385f, 600f, 440f, 600f, 1000f, 0f, 1000f)
            shape(Color(0xFFD9B765), 0f, 610f, 160f, 525f, 390f, 580f, 600f, 500f, 600f, 1000f, 0f, 1000f)
            shape(Color(0xFF9E8745), 0f, 780f, 240f, 670f, 470f, 720f, 600f, 640f, 600f, 1000f, 0f, 1000f)
            repeat(36) { n ->
                val x = (n * 97 % 600).toFloat()
                val y = 720f + n * 43 % 270
                drawLine(Color(0xFFF6D689), Offset(x, y + 35f), Offset(x + 4f, y), 2f)
                drawOval(Color(0xFFF6D689), Offset(x, y - 9f), Size(8f, 20f))
            }
        }
        ManaLand.ISLAND -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF478BA5), Color(0xFF18455E)), startY = 470f, endY = 1000f),
                Offset(0f, 470f), Size(600f, 530f))
            shape(Color(0xFF47756C), 40f, 530f, 145f, 455f, 180f, 355f, 230f, 410f, 280f, 510f, 350f, 550f)
            shape(Color(0xFFBDAD83), 35f, 540f, 185f, 510f, 290f, 530f, 360f, 565f, 130f, 575f)
            repeat(22) { n ->
                val x = (n * 83 % 530).toFloat()
                val y = 610f + n * 17f
                drawLine(Color(0xFFBAE2DD).copy(alpha = 0.6f), Offset(x, y), Offset(x + 65f, y), 3f)
            }
            shape(Color(0xFF244650), 0f, 815f, 90f, 730f, 175f, 850f, 225f, 1000f, 0f, 1000f)
        }
        ManaLand.SWAMP -> {
            shape(Color(0xFF4F6262), 0f, 540f, 140f, 490f, 360f, 525f, 600f, 460f, 600f, 1000f, 0f, 1000f)
            drawOval(Color(0xFF7C8B83), Offset(60f, 640f), Size(550f, 280f))
            repeat(8) { n ->
                val x = (n * 137 % 600).toFloat()
                val base = 610f + n % 3 * 110f
                tree(x, base, 190f + n % 3 * 65f, Color(0xFF313741), bare = true)
            }
            repeat(4) { n ->
                drawOval(Color(0xFFCCD0BB).copy(alpha = 0.3f), Offset(n * 100f - 100f, 650f + n * 70f), Size(480f, 24f))
            }
        }
        ManaLand.MOUNTAIN -> {
            shape(Color(0xFF775665), 0f, 600f, 170f, 270f, 290f, 480f, 405f, 220f, 600f, 600f, 600f, 1000f, 0f, 1000f)
            shape(Color(0xFFE0C7B0), 110f, 390f, 170f, 270f, 225f, 365f, 185f, 342f, 163f, 365f, 140f, 347f)
            shape(Color(0xFFE0C7B0), 350f, 350f, 405f, 220f, 485f, 375f, 420f, 333f, 390f, 348f)
            shape(Color(0xFF9F5140), 0f, 720f, 110f, 530f, 230f, 660f, 350f, 440f, 600f, 745f, 600f, 1000f, 0f, 1000f)
            shape(Color(0xFF583C3D), 0f, 870f, 210f, 740f, 355f, 800f, 500f, 620f, 600f, 765f, 600f, 1000f, 0f, 1000f)
        }
        ManaLand.FOREST -> {
            shape(Color(0xFF4F8170), 0f, 580f, 130f, 420f, 300f, 515f, 470f, 390f, 600f, 490f, 600f, 1000f, 0f, 1000f)
            repeat(18) { n ->
                tree((n * 83 % 650 - 25).toFloat(), 610f + n % 4 * 105f,
                    200f + n % 3 * 70f, if (n < 9) Color(0xFF3F6B55) else Color(0xFF244D40))
            }
            shape(Color(0xFF93A276), 190f, 1000f, 290f, 710f, 335f, 680f, 300f, 800f, 410f, 1000f)
        }
        ManaLand.WASTES -> {
            shape(Color(0xFF9695A4), 0f, 590f, 110f, 475f, 245f, 550f, 425f, 450f, 600f, 570f, 600f, 1000f, 0f, 1000f)
            shape(Color(0xFF666478), 0f, 780f, 230f, 660f, 430f, 720f, 600f, 630f, 600f, 1000f, 0f, 1000f)
            repeat(7) { n ->
                val x = 30f + n * 85f
                val base = 690f + n % 3 * 105f
                val height = 85f + n % 3 * 75f
                shape(Color(0xFFCECADA), x, base, x + 20f, base - height, x + 40f, base - height - 35f, x + 55f, base)
                shape(Color(0xFF858297), x + 20f, base - height, x + 40f, base - height - 35f, x + 55f, base, x + 35f, base)
            }
        }
    }
}

private fun DrawScope.shape(color: Color, vararg coordinates: Float) {
    val path = Path().apply {
        moveTo(coordinates[0], coordinates[1])
        for (i in 2 until coordinates.size step 2) lineTo(coordinates[i], coordinates[i + 1])
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.tree(x: Float, base: Float, height: Float, color: Color, bare: Boolean = false) {
    drawLine(color, Offset(x, base), Offset(x + 8f, base - height), if (bare) 10f else 14f)
    if (bare) {
        for (n in 1..3) {
            val y = base - height * n / 4f
            drawLine(color, Offset(x, y), Offset(x - 45f, y - 55f), 6f)
            drawLine(color, Offset(x + 3f, y - 30f), Offset(x + 55f, y - 65f), 5f)
        }
    } else {
        for (n in 0..2) {
            val tip = base - height + n * height * 0.22f
            val halfWidth = height * (0.18f + n * 0.06f)
            shape(color, x + 8f, tip, x - halfWidth, tip + height * 0.48f, x + halfWidth, tip + height * 0.48f)
        }
    }
}
