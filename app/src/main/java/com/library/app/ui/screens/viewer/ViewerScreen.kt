@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.library.app.ui.screens.viewer

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.library.app.data.files.PreviewKind
import com.library.app.data.files.loadSampledBitmap
import com.library.app.data.files.previewKind
import com.library.app.data.files.readTextFile
import com.library.app.data.model.Entry
import com.library.app.data.model.EntryType
import com.library.app.ui.LocalFileStore
import com.library.app.ui.LocalRepository
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.EmptyState
import com.library.app.ui.components.LibraryTopBar
import com.library.app.ui.components.MarkdownText
import com.library.app.ui.screens.lock.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.library.app.i18n.tr

/**
 * 内置查看器：
 * 图片左右滑动切换同目录的图、双指缩放；
 * 视频用系统解码器播放，可全屏（全屏不重建播放器，不会从头放）；
 * txt/md 直接读。
 */
@Composable
fun ViewerScreen(entryId: Long, onBack: () -> Unit) {
    val repository = LocalRepository.current
    val fileStore = LocalFileStore.current
    val context = LocalContext.current

    val entry by produceState<Entry?>(null, entryId) { value = repository.getEntry(entryId) }
    val current = entry
    val uri = remember(current) {
        current?.fileUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }
    val kind = remember(current) {
        current?.let { previewKind(it.mimeType, it.fileName) } ?: PreviewKind.OTHER
    }

    var fullscreen by remember { mutableStateOf(false) }

    // 全屏 = 横屏 + 藏状态栏/导航栏；退出时恢复
    val view = LocalView.current
    val activity = context.findActivity()
    DisposableEffect(fullscreen) {
        activity?.requestedOrientation = if (fullscreen) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(fullscreen) {
        val window = context.findActivity()?.window
        val controller: WindowInsetsControllerCompat? =
            window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 视频单独走一条路：全屏只是换布局，播放器实例不变，播放进度不会丢
    if (current != null && uri != null && kind == PreviewKind.VIDEO) {
        VideoScreen(
            uri = uri,
            title = current.title.ifBlank { current.fileName.orEmpty() },
            subtitle = current.fileName,
            fullscreen = fullscreen,
            onToggleFullscreen = { fullscreen = !fullscreen },
            onOpenExternal = { fileStore.open(uri.toString(), current.mimeType) },
            onBack = {
                if (fullscreen) fullscreen = false else onBack()
            },
        )
        return
    }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = current?.title?.ifBlank { current?.fileName.orEmpty() } ?: "打开中…".tr(),
                subtitle = current?.fileName,
                onBack = onBack,
                actions = {
                    if (uri != null) {
                        TextButton(onClick = { fileStore.open(uri.toString(), current?.mimeType) }) {
                            Text("其他应用".tr())
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                uri == null -> EmptyState(
                    icon = AppIcons.Doc,
                    title = "打不开".tr(),
                    message = "这条记录没有关联文件".tr(),
                )

                kind == PreviewKind.IMAGE -> ImagePages(
                    current = current,
                    currentUri = uri,
                    repository = repository,
                )

                kind == PreviewKind.MARKDOWN -> TextPreview(uri, markdown = true)

                kind == PreviewKind.TEXT -> TextPreview(uri, markdown = false)

                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        icon = AppIcons.Doc,
                        title = "这个格式内置打不开".tr(),
                        message = "点右上角「其他应用」交给系统里的程序处理".tr(),
                    )
                }
            }
        }
    }
}

/** 同目录的图片连成一串，左右滑动切换；放大后锁住翻页，避免和拖动打架。 */
@Composable
private fun ImagePages(
    current: Entry,
    currentUri: Uri,
    repository: com.library.app.data.Repository,
) {
    val siblings by produceState<List<Entry>>(emptyList(), current.moduleId, current.folderId) {
        value = withContext(Dispatchers.IO) {
            repository.listEntries(current.moduleId, current.folderId)
                .filter {
                    it.type == EntryType.FILE &&
                        previewKind(it.mimeType, it.fileName) == PreviewKind.IMAGE
                }
        }
    }

    // 目录还没查完时先别建分页器：分页器的初始页只在创建时读一次，
    // 那时候列表还是空的，就会永远停在第 1 张。
    if (siblings.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    val uris = remember(siblings, currentUri) {
        siblings.mapNotNull { item -> item.fileUri?.let { runCatching { Uri.parse(it) }.getOrNull() } }
            .ifEmpty { listOf(currentUri) }
    }
    val initialPage = uris.indexOfFirst { it.toString() == currentUri.toString() }
        .coerceAtLeast(0)

    val pagerState = rememberPagerState(initialPage = initialPage) { uris.size }
    var zoomed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize().background(Color.Black),
        ) { page ->
            ZoomableImage(
                uri = uris[page],
                // 只有当前页的缩放状态才决定能不能翻页，邻页报上来的一律忽略
                onZoomChanged = { value ->
                    if (page == pagerState.currentPage) zoomed = value
                },
            )
        }

        if (uris.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1} / ${uris.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(uri: Uri, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { loadSampledBitmap(context, uri) }
    }
    val image = bitmap

    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(uri) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(uri) {
                // 单指、未放大时不消费事件，交给外面的翻页；双指或已放大才接管
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val downCount = event.changes.count { it.pressed }
                        if (downCount > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            event.changes.forEach { it.consume() }
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale <= 1.01f) Offset.Zero else offset + pan
                        }
                        pressed = event.changes.any { it.pressed }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
}

/**
 * 视频页：用 TextureView + MediaPlayer 自己播。
 * 不用 VideoView 是因为它内部的 SurfaceView 在 Compose 里常被上层绘制盖住，只有声音没有画面。
 * 全屏只改容器尺寸，播放器实例不变，点全屏不会从头播。
 */
@Composable
private fun VideoScreen(
    uri: Uri,
    title: String,
    subtitle: String?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenExternal: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var duration by remember(uri) { mutableIntStateOf(0) }
    var position by remember(uri) { mutableIntStateOf(0) }
    var aspect by remember(uri) { mutableFloatStateOf(16f / 9f) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableIntStateOf(0) }

    // 播放中每 400ms 刷一次进度
    LaunchedEffect(player, playing) {
        while (playing) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(400)
        }
    }

    DisposableEffect(uri) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 非全屏时给顶栏和底部进度条让出位置，避免竖屏视频被挡
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = if (fullscreen) 0.dp else 88.dp,
                    bottom = if (fullscreen) 0.dp else 56.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val containerRatio = maxWidth.value / maxHeight.value
            val sizeModifier = if (fullscreen) {
                if (aspect >= containerRatio) {
                    Modifier.fillMaxWidth().aspectRatio(aspect)
                } else {
                    Modifier.fillMaxHeight().aspectRatio(aspect)
                }
            } else {
                Modifier.fillMaxWidth().aspectRatio(aspect)
            }

            Box(modifier = sizeModifier.background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    texture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    runCatching {
                                        MediaPlayer().apply {
                                            setDataSource(ctx, uri)
                                            setSurface(Surface(texture))
                                            setOnPreparedListener { mp ->
                                                if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                                    aspect = mp.videoWidth.toFloat() / mp.videoHeight.toFloat()
                                                }
                                                duration = mp.duration.coerceAtLeast(0)
                                                mp.start()
                                                playing = true
                                            }
                                            setOnVideoSizeChangedListener { _, w, h ->
                                                if (w > 0 && h > 0) aspect = w.toFloat() / h.toFloat()
                                            }
                                            setOnCompletionListener { playing = false }
                                            setOnErrorListener { _, what, extra ->
                                                error = "系统解码器打不开这个视频（$what/$extra），可以点右上角用其他应用".tr()
                                                playing = false
                                                true
                                            }
                                            prepareAsync()
                                        }.also { created -> player = created }
                                    }.onFailure {
                                        error = "打不开这个视频：${it.message ?: "未知原因"}".tr()
                                    }
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    texture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                    runCatching { player?.release() }
                                    player = null
                                    playing = false
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                error?.let { message ->
                    Text(
                        text = message,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                if (error == null) {
                    // 中间那颗播放/暂停
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable {
                                val mp = player ?: return@clickable
                                if (mp.isPlaying) {
                                    mp.pause()
                                    playing = false
                                } else {
                                    if (duration > 0 && position >= duration - 300) mp.seekTo(0)
                                    mp.start()
                                    playing = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (playing) "\u2758\u2758" else "\u25B6",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }

        if (!fullscreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                LibraryTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    actions = {
                        TextButton(onClick = onOpenExternal) { Text("其他应用".tr()) }
                    },
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(if (scrubbing) scrubValue else position),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = (if (scrubbing) scrubValue else position).toFloat()
                        .coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    onValueChange = {
                        scrubbing = true
                        scrubValue = it.toInt()
                    },
                    onValueChangeFinished = {
                        player?.seekTo(scrubValue)
                        position = scrubValue
                        scrubbing = false
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    text = formatTime(duration),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        TextButton(
            onClick = onToggleFullscreen,
            modifier = Modifier
                .align(if (fullscreen) Alignment.TopEnd else Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    top = if (fullscreen) 16.dp else 0.dp,
                    bottom = if (fullscreen) 16.dp else 64.dp,
                )
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
        ) {
            Text(if (fullscreen) "退出全屏".tr() else "全屏".tr(), color = Color.White)
        }
    }
}

private fun formatTime(millis: Int): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun TextPreview(uri: Uri, markdown: Boolean) {
    val context = LocalContext.current
    val text by produceState<String?>(null, uri) {
        value = withContext(Dispatchers.IO) { readTextFile(context, uri) }
    }
    val content = text

    if (content == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("读不出来，可能不是文本文件".tr(), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (markdown) {
            MarkdownText(text = content)
        } else {
            SelectionContainer {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    ),
                )
            }
        }
    }
}
