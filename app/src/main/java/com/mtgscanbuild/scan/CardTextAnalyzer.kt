package com.mtgscanbuild.scan

import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mtgscanbuild.data.CardNameIndex

/**
 * Where the on-screen card guide sits, as fractions (0..1) of the preview view, plus the
 * view's aspect ratio (width / height). Used to read only the text inside the guide.
 */
data class GuideRegion(val left: Float, val top: Float, val right: Float, val bottom: Float, val viewAspect: Float)

/** Feeds camera frames to ML Kit's on-device text recognizer (throttled). */
class CardTextAnalyzer(private val onResult: (ScanReading) -> Unit) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var busy = false
    @Volatile var guide: GuideRegion? = null
    @Volatile var enabled = true
    private var lastRun = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = proxy.image
        if (media == null || !enabled || busy || now - lastRun < 300) { proxy.close(); return }
        busy = true
        lastRun = now
        val rotation = proxy.imageInfo.rotationDegrees
        val region = guideInImage(proxy.width, proxy.height, proxy.cropRect, rotation, guide)
        try {
            recognizer.process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener { onResult(ScanReading.from(it, region)) }
                .addOnCompleteListener { busy = false; proxy.close() }
        } catch (e: Exception) {
            busy = false
            proxy.close()
        }
    }

    fun close() = recognizer.close()

    companion object {
        /** Rotates a rect in sensor-buffer coordinates into upright (display) coordinates. */
        fun rotate(r: Rect, w: Int, h: Int, rotation: Int): Rect = when ((rotation % 360 + 360) % 360) {
            90 -> Rect(h - r.bottom, r.left, h - r.top, r.right)
            180 -> Rect(w - r.right, h - r.bottom, w - r.left, h - r.top)
            270 -> Rect(r.top, w - r.right, r.bottom, w - r.left)
            else -> Rect(r)
        }

        /**
         * Maps the on-screen guide into upright image coordinates. The preview uses FILL_CENTER,
         * so the visible area is the largest centred region of the frame with the view's aspect.
         */
        fun guideInImage(w: Int, h: Int, crop: Rect?, rotation: Int, g: GuideRegion?): RectF {
            val c = rotate(crop?.takeIf { !it.isEmpty } ?: Rect(0, 0, w, h), w, h, rotation)
            var vl = c.left.toFloat(); var vt = c.top.toFloat()
            var vw = c.width().toFloat(); var vh = c.height().toFloat()
            if (g == null) return RectF(vl + vw * 0.04f, vt + vh * 0.08f, vl + vw * 0.96f, vt + vh * 0.92f)
            if (g.viewAspect > 0f) {
                if (vw / vh > g.viewAspect) { val nw = vh * g.viewAspect; vl += (vw - nw) / 2; vw = nw }
                else { val nh = vw / g.viewAspect; vt += (vh - nh) / 2; vh = nh }
            }
            // Allow some slack around the guide: cards are rarely held perfectly inside it.
            val padX = (g.right - g.left) * 0.10f
            val padY = (g.bottom - g.top) * 0.06f
            return RectF(
                vl + vw * (g.left - padX), vt + vh * (g.top - padY),
                vl + vw * (g.right + padX), vt + vh * (g.bottom + padY),
            )
        }
    }
}

data class OcrLine(val text: String, val top: Int, val left: Int, val height: Int)

/** OCR lines from one frame (only those inside the on-screen card guide). */
class ScanReading(val lines: List<OcrLine>) {
    companion object {
        fun from(t: Text, region: RectF): ScanReading {
            val out = mutableListOf<OcrLine>()
            for (b in t.textBlocks) for (l in b.lines) {
                val box = l.boundingBox ?: continue
                if (!region.contains(box.exactCenterX(), box.exactCenterY())) continue
                out += OcrLine(l.text, box.top, box.left, box.height())
            }
            return ScanReading(out.sortedBy { it.top })
        }
    }
}

data class ScanMatch(val name: String, val score: Double, val setCode: String?, val collectorNumber: String?)

object ScanParser {
    private val setLine = Regex("\\b([A-Z0-9]{3,5})\\s*[•·*.\\-]?\\s*(EN|ES|FR|DE|IT|PT|JP|JA|KO|RU|ZHS|ZHT|PH)\\b")
    private val numberLine = Regex("\\b(\\d{1,4})(?:\\s*/\\s*\\d{1,4})?\\s+([CURMSLTP])\\b")
    private val letters = Regex("[A-Za-z]")

    fun parse(r: ScanReading, index: CardNameIndex): ScanMatch? {
        if (r.lines.isEmpty()) return null
        val candidates = r.lines.filter { l -> letters.findAll(l.text).count() >= 3 }.take(5)
        var best: CardNameIndex.Match? = null
        for ((i, l) in candidates.withIndex()) {
            val m = index.match(l.text) ?: continue
            val adjusted = m.score - i * 0.02 // the title is the top line of the card
            if (best == null || adjusted > best.score) best = CardNameIndex.Match(m.name, adjusted)
            if (i == 0 && m.score >= 0.95) break
        }
        val b = best ?: return null
        var set: String? = null
        var num: String? = null
        for (l in r.lines.asReversed()) {
            if (set == null) setLine.find(l.text)?.let { set = it.groupValues[1] }
            if (num == null) numberLine.find(l.text)?.let { num = it.groupValues[1].trimStart('0').ifEmpty { "0" } }
        }
        return ScanMatch(b.name, b.score, set, num)
    }
}
