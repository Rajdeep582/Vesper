package com.rajdeep.vesper.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

class PreparedImage(val bytes: ByteArray, val width: Int, val height: Int, val blurhash: String, val mime: String)

object ImageCompressor {
    const val MAX_EDGE = 1600

    /** Decodes with orientation applied, downsizes to ≤1600 px, re-encodes as WebP (no EXIF/GPS survives). */
    suspend fun prepare(ctx: Context, uri: Uri): PreparedImage = withContext(Dispatchers.Default) {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, MAX_EDGE.toFloat() / max(w, h))
            decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        // WhatsApp-standard feel: ≤1600 px, WebP q≈74 (typ. 150–400 KB); step down gently, never below 58 (keeps detail).
        var quality = 74
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(fmt, quality, bos)
            out = bos.toByteArray()
            quality -= 8
        } while (out.size > 450_000 && quality >= 58)
        val hash = BlurHash.encode(Bitmap.createScaledBitmap(bmp, 32, (32f * bmp.height / bmp.width).roundToInt().coerceIn(1, 64), true), 4, 3)
        PreparedImage(out, bmp.width, bmp.height, hash, "image/webp")
    }
}

/** Minimal BlurHash encoder/decoder (https://blurha.sh, MIT algorithm). */
object BlurHash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    private fun enc83(v: Int, len: Int): String = buildString { for (i in 1..len) append(CHARS[(v / 83.0.pow(len - i).toInt()) % 83]) }
    private fun dec83(s: String): Int = s.fold(0) { acc, c -> acc * 83 + CHARS.indexOf(c) }
    private fun s2l(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
    private fun l2s(v: Double): Int { val x = v.coerceIn(0.0, 1.0); return if (x <= 0.0031308) (x * 12.92 * 255 + 0.5).toInt() else ((1.055 * x.pow(1 / 2.4) - 0.055) * 255 + 0.5).toInt() }
    private fun signPow(v: Double, e: Double) = sign(v) * kotlin.math.abs(v).pow(e)

    fun encode(bmp: Bitmap, cx: Int, cy: Int): String {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val factors = Array(cx * cy) { DoubleArray(3) }
        for (j in 0 until cy) for (i in 0 until cx) {
            val norm = if (i == 0 && j == 0) 1.0 else 2.0
            var r = 0.0; var g = 0.0; var b = 0.0
            for (y in 0 until h) for (x in 0 until w) {
                val basis = norm * cos(Math.PI * i * x / w) * cos(Math.PI * j * y / h)
                val p = px[y * w + x]
                r += basis * s2l((p shr 16) and 0xff); g += basis * s2l((p shr 8) and 0xff); b += basis * s2l(p and 0xff)
            }
            val scale = 1.0 / (w * h)
            factors[j * cx + i] = doubleArrayOf(r * scale, g * scale, b * scale)
        }
        val dc = factors[0]
        val ac = factors.drop(1)
        val sb = StringBuilder(enc83((cx - 1) + (cy - 1) * 9, 1))
        val maxAc = if (ac.isEmpty()) 1.0 else ac.maxOf { f -> f.maxOf { kotlin.math.abs(it) } }
        val qMax = if (ac.isEmpty()) 0 else (maxAc * 166 - 0.5).toInt().coerceIn(0, 82)
        sb.append(enc83(qMax, 1))
        val acMax = if (ac.isEmpty()) 1.0 else (qMax + 1) / 166.0
        sb.append(enc83((l2s(dc[0]) shl 16) + (l2s(dc[1]) shl 8) + l2s(dc[2]), 4))
        ac.forEach { f ->
            fun q(v: Double) = (signPow(v / acMax, 0.5) * 9 + 9.5).toInt().coerceIn(0, 18)
            sb.append(enc83(q(f[0]) * 19 * 19 + q(f[1]) * 19 + q(f[2]), 2))
        }
        return sb.toString()
    }

    /** Decodes into a small bitmap used as a placeholder while the real image downloads/decrypts. */
    fun decode(hash: String, w: Int = 32, h: Int = 32): Bitmap? = runCatching {
        val size = dec83(hash.substring(0, 1))
        val cx = size % 9 + 1; val cy = size / 9 + 1
        val maxAc = (dec83(hash.substring(1, 2)) + 1) / 166.0
        val colors = Array(cx * cy) { DoubleArray(3) }
        val dc = dec83(hash.substring(2, 6))
        colors[0] = doubleArrayOf(s2l(dc shr 16), s2l((dc shr 8) and 255), s2l(dc and 255))
        for (i in 1 until cx * cy) {
            val v = dec83(hash.substring(4 + i * 2, 6 + i * 2))
            fun d(q: Int) = signPow((q - 9) / 9.0, 2.0) * maxAc
            colors[i] = doubleArrayOf(d(v / (19 * 19)), d((v / 19) % 19), d(v % 19))
        }
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0.0; var g = 0.0; var b = 0.0
            for (j in 0 until cy) for (i in 0 until cx) {
                val basis = cos(Math.PI * x * i / w) * cos(Math.PI * y * j / h)
                val c = colors[i + j * cx]; r += c[0] * basis; g += c[1] * basis; b += c[2] * basis
            }
            px[y * w + x] = (0xff shl 24) or (l2s(r) shl 16) or (l2s(g) shl 8) or l2s(b)
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

/** Opus/OGG mono voice recorder (≈24 kbps). Writes to an app-private temp file that is deleted after upload. */
@Singleton
class VoiceRecorder @Inject constructor(@ApplicationContext private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val amps = ArrayList<Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var poll: Job? = null
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _elapsed = MutableStateFlow(0L)
    val elapsed: StateFlow<Long> = _elapsed
    var onMaxReached: () -> Unit = {}

    class Result(val file: File, val durationMs: Long, val waveform: List<Int>)

    fun start(maxMs: Long): Boolean {
        cancel()
        val dir = File(ctx.cacheDir, "rec").apply { mkdirs() }
        val f = File(dir, UUID.randomUUID().toString() + ".ogg")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            // VOICE_COMMUNICATION enables the platform's noise suppression / echo cancel / AGC for clearer speech.
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(20_000) // Opus 20 kbps mono: clear speech at ~150 KB/min
            r.setMaxDuration(maxMs.toInt())
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached() }
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r; file = f; startedAt = System.currentTimeMillis(); amps.clear()
            poll = scope.launch {
                while (isActive) {
                    val a = runCatching { r.maxAmplitude }.getOrDefault(0)
                    amps += a
                    _level.value = (a / 20000f).coerceIn(0f, 1f)
                    _elapsed.value = System.currentTimeMillis() - startedAt
                    delay(60)
                }
            }
            true
        }.getOrElse { r.release(); f.delete(); false }
    }

    fun stop(): Result? {
        val r = recorder ?: return null
        val f = file
        poll?.cancel()
        val dur = System.currentTimeMillis() - startedAt
        val ok = runCatching { r.stop() }.isSuccess
        r.release(); recorder = null; file = null
        _level.value = 0f; _elapsed.value = 0
        if (!ok || f == null || dur < 400) { f?.delete(); return null }
        return Result(f, dur, buckets(amps, 64))
    }

    fun cancel() {
        poll?.cancel()
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete(); file = null
        _level.value = 0f; _elapsed.value = 0
    }

    private fun buckets(src: List<Int>, n: Int): List<Int> {
        if (src.isEmpty()) return List(n) { 0 }
        val peak = max(1, src.max())
        return List(n) { i ->
            val from = i * src.size / n; val to = max(from + 1, (i + 1) * src.size / n)
            val slice = src.subList(from.coerceAtMost(src.size - 1), to.coerceAtMost(src.size))
            (sqrt(slice.average() / peak) * 100).roundToInt().coerceIn(0, 100)
        }
    }
}

/** Plays decrypted voice notes straight from memory — plaintext audio never touches disk. */
@OptIn(UnstableApi::class)
@Singleton
class VoicePlayer @Inject constructor(@ApplicationContext private val ctx: Context) {
    data class State(val id: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val speed: Float = 1f)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private fun ensure(): ExoPlayer = player ?: ExoPlayer.Builder(ctx).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _state.value = _state.value.copy(playing = isPlaying) }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_ENDED) { _state.value = _state.value.copy(playing = false, positionMs = 0); p.pause(); p.seekTo(0) }
                if (s == Player.STATE_READY) _state.value = _state.value.copy(durationMs = p.duration.coerceAtLeast(0))
            }
        })
        player = p
    }

    fun play(id: String, bytes: ByteArray, durationHint: Long) {
        val p = ensure()
        if (_state.value.id == id) { if (p.isPlaying) p.pause() else p.play(); return }
        val factory = DataSource.Factory { ByteArrayDataSource(bytes) }
        p.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("vesper://voice/$id")))
        p.playbackParameters = PlaybackParameters(_state.value.speed)
        p.prepare(); p.play()
        _state.value = State(id, true, 0, durationHint, _state.value.speed)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) { _state.value = _state.value.copy(positionMs = p.currentPosition); delay(50) }
        }
    }

    fun seek(id: String, fraction: Float) {
        val p = player ?: return
        if (_state.value.id != id) return
        val d = if (p.duration > 0) p.duration else _state.value.durationMs
        p.seekTo((d * fraction).toLong())
    }

    fun cycleSpeed() {
        val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.playbackParameters = PlaybackParameters(next)
        _state.value = _state.value.copy(speed = next)
    }

    fun stop() { player?.stop(); ticker?.cancel(); _state.value = State(speed = _state.value.speed) }
}
