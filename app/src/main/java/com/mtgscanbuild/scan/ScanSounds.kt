package com.mtgscanbuild.scan

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import com.mtgscanbuild.data.AppSettings
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class ScanSound(val label: String) {
    CHIME("Chime"),
    BEEP("Classic scanner beep"),
    COIN("Coin"),
    MANA("Mana sparkle"),
    BELL("Bell ding"),
    LASER("Laser"),
    THUMP("Deep thump"),
    CUSTOM("My own sound"),
}

/**
 * Plays the "card scanned" sound. Sounds are synthesized and played on the media stream, so they
 * work with the phone on vibrate (unlike notification-stream tones) and follow the media volume.
 */
class ScanSounds(context: Context, private val settings: AppSettings) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    var enabled: Boolean
        get() = settings.soundOn
        set(v) { settings.soundOn = v }

    var sound: ScanSound
        get() = settings.sound
        set(v) { settings.sound = v }

    /** 0..1, applied on top of the phone's media volume. */
    var volume: Float
        get() = settings.soundVolume
        set(v) { settings.soundVolume = v.coerceIn(0f, 1f) }

    val customUri: Uri? get() = settings.customSoundUri?.let(Uri::parse)
    val customName: String? get() = settings.customSoundName
    private var playerUri: Uri? = null

    val mediaMuted: Boolean get() = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    private var track: AudioTrack? = null
    private var trackSound: ScanSound? = null
    private var player: MediaPlayer? = null
    private val stopCustom = Runnable { player?.takeIf { it.isPlaying }?.pause() }

    /** Plays the selected sound if sounds are enabled. */
    fun play() { if (enabled) preview() }

    /** Plays the selected sound regardless of the on/off setting. */
    fun preview() {
        try {
            if (sound == ScanSound.CUSTOM) playCustom() else playSynth(sound)
        } catch (e: Exception) {
            Log.w("MtgScan", "Scan sound failed", e)
        }
    }

    /** Stores a user-picked audio file (keeps read access across restarts). */
    fun setCustom(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        settings.customSoundUri = uri.toString()
        settings.customSoundName = name ?: "Custom sound"
        releasePlayer()
        sound = ScanSound.CUSTOM
    }

    private fun playSynth(s: ScanSound) {
        val t = track?.takeIf { trackSound == s } ?: run {
            releaseTrack()
            val pcm = Synth.render(s)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Synth.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .also { it.write(pcm, 0, pcm.size); track = it; trackSound = s }
        }
        if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
        t.reloadStaticData()
        t.setVolume(volume)
        t.play()
    }

    private fun playCustom() {
        val uri = customUri ?: return playSynth(ScanSound.CHIME)
        if (playerUri != uri) releasePlayer()
        val p = player ?: MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(app, uri)
            prepare()
        }.also { player = it; playerUri = uri }
        main.removeCallbacks(stopCustom)
        p.setVolume(volume, volume)
        p.seekTo(0)
        p.start()
        // Keep long files (e.g. songs) from playing for ages on every scan.
        main.postDelayed(stopCustom, 3000)
    }

    private fun releaseTrack() {
        track?.release(); track = null; trackSound = null
    }

    private fun releasePlayer() {
        main.removeCallbacks(stopCustom)
        player?.release(); player = null; playerUri = null
    }

    fun release() { releaseTrack(); releasePlayer() }
}

/** Tiny synthesizer for the built-in scan sounds. */
internal object Synth {
    const val RATE = 44100

    private enum class Wave { SINE, TRIANGLE, SQUARE, BELL }

    private class Note(
        val f0: Double, val ms: Int, val wave: Wave = Wave.SINE,
        val f1: Double = f0, val decay: Double = 5.0, val gain: Double = 1.0,
    )

    private fun notes(s: ScanSound): List<Note> = when (s) {
        ScanSound.CHIME -> listOf(Note(987.77, 90, decay = 3.0), Note(1318.51, 260, decay = 5.0))
        ScanSound.BEEP -> listOf(Note(1850.0, 140, Wave.TRIANGLE, decay = 0.6))
        ScanSound.COIN -> listOf(Note(987.77, 70, Wave.SQUARE, decay = 0.5, gain = 0.45), Note(1318.51, 330, Wave.SQUARE, decay = 4.0, gain = 0.45))
        ScanSound.MANA -> listOf(
            Note(783.99, 55, decay = 2.0), Note(987.77, 55, decay = 2.0),
            Note(1174.66, 55, decay = 2.0), Note(1567.98, 280, Wave.BELL, decay = 5.0),
        )
        ScanSound.BELL -> listOf(Note(1760.0, 700, Wave.BELL, decay = 6.0))
        ScanSound.LASER -> listOf(Note(2200.0, 240, Wave.SQUARE, f1 = 260.0, decay = 2.5, gain = 0.4))
        ScanSound.THUMP -> listOf(Note(170.0, 220, Wave.SINE, f1 = 55.0, decay = 7.0))
        ScanSound.CUSTOM -> notes(ScanSound.CHIME)
    }

    fun render(s: ScanSound): ShortArray {
        val list = notes(s)
        val out = ShortArray(list.sumOf { it.ms * RATE / 1000 })
        var pos = 0
        for (n in list) {
            val len = n.ms * RATE / 1000
            val attack = RATE * 4 / 1000
            var phase = 0.0
            for (i in 0 until len) {
                val x = i.toDouble() / len
                val f = n.f0 + (n.f1 - n.f0) * x
                phase += 2 * PI * f / RATE
                val v = when (n.wave) {
                    Wave.SINE -> sin(phase)
                    Wave.TRIANGLE -> 2 / PI * kotlin.math.asin(sin(phase))
                    Wave.SQUARE -> if (sin(phase) >= 0) 1.0 else -1.0
                    Wave.BELL -> (sin(phase) + 0.5 * sin(2.76 * phase) + 0.25 * sin(5.4 * phase)) / 1.75
                }
                val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-n.decay * x) *
                    // Short fade-out so notes never end with a click.
                    minOf(1.0, (len - i).toDouble() / attack)
                out[pos + i] = (v * env * n.gain * 0.9 * Short.MAX_VALUE).toInt().toShort()
            }
            pos += len
        }
        return out
    }
}
