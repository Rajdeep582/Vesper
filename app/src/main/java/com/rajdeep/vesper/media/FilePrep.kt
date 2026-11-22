package com.rajdeep.vesper.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.rajdeep.vesper.core.crypto.MediaCipher
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.MediaChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

class FileTooLarge(val bytes: Long) : Exception("Files can be up to 10 MB")

/** A file made ready to send: packed (compressed), encrypted in parts, staged on disk. */
class PreparedFile(
    val name: String,
    val mime: String,
    val origBytes: Long,
    val codec: String,
    val key: ByteArray,
    val dir: File,
    val chunks: List<MediaChunk>,
) { val cipherBytes: Long get() = chunks.sumOf { it.bytes } }

/**
 * WhatsApp-style "send small" packing, all on the phone before encryption (the server only ever sees ciphertext):
 *  - photos → WebP, longest side ≤ 2560 px, quality 75, metadata stripped
 *  - videos → H.264 ≤ 720p @ ~1.5 Mb/s, AAC (kept only if smaller than the original)
 *  - everything else → XZ/LZMA2 (the strongest general-purpose codec), skipped when the data is already compressed
 * Then the packed bytes are split into ≤ 8 MB parts, each AES-256-GCM encrypted with one per-file key.
 */
object FilePrep {
    private const val XZ = "xz"
    private const val NONE = "none"
    private val PRECOMPRESSED = setOf(
        "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "zst", "apk", "aab", "jar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub",
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp3", "m4a", "aac", "ogg", "opus", "flac", "mp4", "mkv", "webm", "mov", "3gp",
    )

    fun meta(ctx: Context, uri: Uri): Triple<String, Long, String> {
        var name = "file"; var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        return Triple(name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120), size, mime)
    }

    suspend fun prepare(ctx: Context, uri: Uri, isAdmin: Boolean): PreparedFile = withContext(Dispatchers.IO) {
        val (origName, metaSize, mime) = meta(ctx, uri)
        val work = File(ctx.cacheDir, "fileprep/" + UUID.randomUUID()).apply { mkdirs() }
        try {
            // Copy the original once (streams can't be re-read, and the size from metadata can lie).
            val raw = File(work, "raw")
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                FileOutputStream(raw).use { out ->
                    val buf = ByteArray(256 * 1024); var total = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n
                        if (!isAdmin && total > Limits.MAX_FILE_BYTES) throw FileTooLarge(max(total, metaSize))
                        out.write(buf, 0, n)
                    }
                }
            }
            val origBytes = raw.length()
            val ext = origName.substringAfterLast('.', "").lowercase()
            var name = origName
            var outMime = mime
            var packed = raw
            var codec = NONE

            when {
                mime.startsWith("image/") && ext !in setOf("gif", "svg") && mime != "image/gif" -> runCatching {
                    val f = File(work, "img.webp")
                    shrinkImage(ctx, uri, f)
                    if (f.length() in 1 until raw.length()) {
                        packed = f; outMime = "image/webp"; name = origName.substringBeforeLast('.', origName) + ".webp"
                    }
                }
                mime.startsWith("video/") -> runCatching {
                    val f = File(work, "video.mp4")
                    if (transcode(ctx, uri, f) && f.length() in 1 until raw.length()) {
                        packed = f; outMime = "video/mp4"; name = origName.substringBeforeLast('.', origName) + ".mp4"
                    }
                }
                ext in PRECOMPRESSED || mime.startsWith("audio/") -> Unit // already as small as it gets
                else -> {
                    val f = File(work, "data.xz")
                    // Strongest preset that fits comfortably in phone memory.
                    // Max-effort LZMA2 settings, but a dictionary no bigger than the file (≤ 4 MB) keeps memory ~50 MB on phones.
                    var dict = 256 * 1024
                    while (dict < origBytes && dict < 4 * 1024 * 1024) dict *= 2
                    val opts = LZMA2Options(9).apply { dictSize = dict }
                    FileInputStream(raw).use { i -> XZOutputStream(FileOutputStream(f), opts).use { o -> i.copyTo(o, 256 * 1024) } }
                    // Keep XZ only when it really saves space (≥ 3%).
                    if (f.length() < raw.length() * 97 / 100) { packed = f; codec = XZ }
                }
            }

            // Encrypt in parts.
            val key = MediaCipher.newKey()
            val outDir = File(ctx.filesDir, "staging/" + UUID.randomUUID()).apply { mkdirs() }
            val chunks = ArrayList<MediaChunk>()
            FileInputStream(packed).use { input ->
                val buf = ByteArray(Limits.FILE_PART_PLAIN.toInt())
                var i = 0
                while (true) {
                    var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    if (n == 0 && i > 0) break
                    val blob = MediaCipher.encrypt(key, if (n == buf.size) buf else buf.copyOf(n))
                    File(outDir, "$i.bin").writeBytes(blob)
                    chunks += MediaChunk("", MediaCipher.sha256Hex(blob), blob.size.toLong())
                    i++
                    if (n < buf.size) break
                }
            }
            PreparedFile(name, outMime, origBytes, codec, key, outDir, chunks)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Reverses packing: decrypted parts are concatenated in [packed]; returns the final file. */
    fun unpack(packed: File, codec: String?, out: File) {
        out.parentFile?.mkdirs()
        if (codec == XZ) {
            XZInputStream(FileInputStream(packed)).use { i -> FileOutputStream(out).use { o -> i.copyTo(o, 256 * 1024) } }
            packed.delete()
        } else if (!packed.renameTo(out)) { packed.copyTo(out, overwrite = true); packed.delete() }
    }

    private fun shrinkImage(ctx: Context, uri: Uri, out: File) {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val w = info.size.width; val h = info.size.height
            val s = minOf(1f, 2560f / max(w, h))
            d.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        @Suppress("DEPRECATION")
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        FileOutputStream(out).use { bmp.compress(fmt, 75, it) }
        bmp.recycle()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun transcode(ctx: Context, uri: Uri, out: File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val encoders = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(1_500_000).build())
                .setEnableFallback(true)
                .build()
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoders)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(true) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (cont.isActive) cont.resume(false) }
                })
                .build()
            // Only ever scale down (never upscale small clips).
            val short = runCatching {
                android.media.MediaMetadataRetriever().use { r ->
                    r.setDataSource(ctx, uri)
                    val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                    val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                    minOf(w, h)
                }
            }.getOrDefault(0)
            val effects = if (short > 720) Effects(emptyList(), listOf(Presentation.createForShortSide(720))) else Effects.EMPTY
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
            runCatching { transformer.start(item, out.absolutePath) }.onFailure { if (cont.isActive) cont.resume(false) }
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
        }
    }
}
