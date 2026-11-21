package com.library.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** 内置查看器能处理的几类文件。 */
enum class PreviewKind { IMAGE, VIDEO, TEXT, MARKDOWN, OTHER }

fun previewKind(mime: String?, name: String?): PreviewKind {
    val lowerName = name.orEmpty().lowercase()
    val extension = lowerName.substringAfterLast('.', "")
    val type = mime.orEmpty().lowercase()

    return when {
        type.startsWith("image/") || extension in IMAGE_EXTS -> PreviewKind.IMAGE
        type.startsWith("video/") || extension in VIDEO_EXTS -> PreviewKind.VIDEO
        extension == "md" || extension == "markdown" -> PreviewKind.MARKDOWN
        type.startsWith("text/") || extension in TEXT_EXTS -> PreviewKind.TEXT
        else -> PreviewKind.OTHER
    }
}

/** 按最长边采样解码，避免一张 5000px 的图直接把内存吃满。 */
fun loadSampledBitmap(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}.getOrNull()

/** 列表里的小缩略图，图片和视频都能取（系统 provider 生成）。 */
fun loadThumbnailBitmap(context: Context, uri: Uri, sizePx: Int = 160): Bitmap? = runCatching {
    context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
}.getOrNull()

/**
 * 读文本文件，最多读 [limitBytes]。
 * UTF-8 解出来如果有大量替换字符，再按 GB18030 试一次（中文 txt 常见）。
 */
fun readTextFile(context: Context, uri: Uri, limitBytes: Int = 1 shl 20): String? = runCatching {
    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            val take = minOf(read, limitBytes - total)
            if (take <= 0) break
            output.write(buffer, 0, take)
            total += take
            if (total >= limitBytes) break
        }
        output.toByteArray()
    } ?: return@runCatching null

    val utf8 = raw.toString(Charsets.UTF_8)
    val broken = utf8.count { it == '\uFFFD' }
    if (broken > 0 && broken > utf8.length / 100) {
        runCatching { raw.toString(Charset.forName("GB18030")) }.getOrDefault(utf8)
    } else {
        utf8
    }
}.getOrNull()

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "ts", "m4v")
private val TEXT_EXTS = setOf(
    "txt", "log", "json", "xml", "csv", "ini", "conf", "yml", "yaml", "properties",
    "kt", "java", "py", "js", "ts", "html", "css", "sh", "sql",
)
