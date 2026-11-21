package com.library.app.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import com.library.app.i18n.tr

private const val MaxScale = 4f
private const val OutputSize = 512

/**
 * 头像裁剪：方形预览 + 拖动 / 双指缩放 / 90° 旋转，确认后导出 512×512。
 *
 * 预览用 graphicsLayer，导出用等价的 Matrix 重算一遍（预览 px → 输出 px 乘个系数），
 * 两条路共用同一组参数，所以看到什么就存什么。
 */
@Composable
fun AvatarCropDialog(
    source: Bitmap,
    onCancel: () -> Unit,
    onDone: (Bitmap) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableIntStateOf(0) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var sidePx by remember { mutableFloatStateOf(0f) }

    val apply: (Float, Int, Offset) -> Unit = { s, r, o ->
        if (sidePx > 0f) {
            val bounded = s.coerceIn(minScaleFor(r, source.width, source.height, sidePx), MaxScale)
            scale = bounded
            rotation = r
            offset = clampOffset(o, bounded, r, source.width, source.height, sidePx)
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text("取消".tr()) }
                    Text(
                        text = "调整头像".tr(),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(
                        enabled = sidePx > 0f,
                        onClick = {
                            onDone(exportSquare(source, rotation, scale, offset, sidePx))
                        },
                    ) {
                        Text("完成".tr(), fontWeight = FontWeight.Medium)
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth - 36.dp, maxHeight - 28.dp)

                    Box(
                        modifier = Modifier
                            .size(side)
                            .clip(RoundedCornerShape(22.dp))
                            .background(androidx.compose.ui.graphics.Color(0xFF121016))
                            .onSizeChanged { sidePx = it.width.toFloat() }
                            .pointerInput(source) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    apply(scale * zoom, rotation, offset + pan)
                                }
                            },
                    ) {
                        Image(
                            bitmap = source.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y,
                                    rotationZ = rotation.toFloat(),
                                    // 变换后的画面得裁回方框里，不然会盖到框外
                                    clip = true,
                                    shape = RoundedCornerShape(22.dp),
                                ),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "拖动挪位置，双指缩放".tr(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { apply(scale, (rotation + 90) % 360, offset) },
                        ) {
                            Text("旋转 90°".tr())
                        }
                        OutlinedButton(onClick = { apply(1f, 0, Offset.Zero) }) {
                            Text("复位".tr())
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "缩放".tr(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = scale,
                            onValueChange = { apply(it, rotation, offset) },
                            valueRange = 1f..MaxScale,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 图在框里铺满所需的最小缩放 —— 旋转 90° 后短边成了宽度，得放大才不会露白。 */
private fun minScaleFor(rotation: Int, srcW: Int, srcH: Int, sidePx: Float): Float {
    val base = sidePx / max(srcW, srcH).toFloat()
    val w = srcW * base
    val h = srcH * base
    val rad = Math.toRadians(rotation.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val needW = sidePx / (w * c + h * s)
    val needH = sidePx / (w * s + h * c)
    return max(1f, max(needW, needH))
}

/** 平移别把图拖出框外：按旋转后的包围盒算可走的最大距离。 */
private fun clampOffset(
    offset: Offset,
    scale: Float,
    rotation: Int,
    srcW: Int,
    srcH: Int,
    sidePx: Float,
): Offset {
    val base = sidePx / max(srcW, srcH).toFloat()
    val rad = Math.toRadians(rotation.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val w = srcW * base * scale
    val h = srcH * base * scale
    val maxX = max(0f, (w * c + h * s) / 2f - sidePx / 2f)
    val maxY = max(0f, (w * s + h * c) / 2f - sidePx / 2f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** 按预览用的同一组参数，把画面画进 512×512。 */
private fun exportSquare(
    source: Bitmap,
    rotation: Int,
    scale: Float,
    offset: Offset,
    sidePx: Float,
): Bitmap {
    val base = sidePx / max(source.width, source.height).toFloat()
    val k = OutputSize / sidePx
    val matrix = Matrix().apply {
        postScale(base, base)
        postTranslate(-source.width * base / 2f, -source.height * base / 2f)
        postScale(scale, scale)
        postRotate(rotation.toFloat())
        postTranslate(offset.x + sidePx / 2f, offset.y + sidePx / 2f)
        postScale(k, k)
    }
    val output = Bitmap.createBitmap(OutputSize, OutputSize, Bitmap.Config.ARGB_8888)
    Canvas(output).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }
    return output
}

/** 读图：按长边采样到 2048 以内，再按 EXIF 转正，免得相机竖拍进来是躺着的。 */
suspend fun loadAvatarSource(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2

        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: return@runCatching null

        val orientation = resolver.openInputStream(uri)?.use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        rotateByExif(decoded, orientation)
    }.getOrNull()
}

private fun rotateByExif(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return source
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
