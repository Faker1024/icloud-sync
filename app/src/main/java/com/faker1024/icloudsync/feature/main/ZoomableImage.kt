package com.faker1024.icloudsync.feature.main

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import kotlin.math.roundToInt

/**
 * Hosts ZoomImage's Android View engine inside Compose. The original image is decoded in tiles,
 * so zooming a very large photo does not require a full-resolution Bitmap in memory.
 */
@Composable
internal fun TiledZoomImage(
    thumbnail: Bitmap,
    imageSource: ImageSource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onViewerChanged: (ZoomImageView?) -> Unit = {},
    onZoomPercentChanged: (Int) -> Unit = {},
    onTap: () -> Unit = {},
) {
    var viewer by remember(imageSource.key) { mutableStateOf<ZoomImageView?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)

    AndroidView(
        factory = { context ->
            ZoomImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                zoomable.setThreeStepScale(true)
                zoomable.setRubberBandScale(true)
                contentDescription?.let { this.contentDescription = it }
                setImageBitmap(thumbnail)
                setSubsamplingImage(imageSource)
                onViewTapListener = OnViewTapListener { _, _ -> currentOnTap() }
                viewer = this
            }
        },
        modifier = modifier,
    )

    DisposableEffect(viewer) {
        val current = viewer
        onViewerChanged(current)
        onDispose { onViewerChanged(null) }
    }
    LaunchedEffect(viewer) {
        viewer?.zoomable?.userTransformState?.collect { transform ->
            onZoomPercentChanged((transform.scaleX * 100f).roundToInt().coerceAtLeast(1))
        }
    }
}

@Composable
internal fun ImmersiveViewerSystemBars(controlsVisible: Boolean) {
    val composeView = LocalView.current
    val dialogWindow = (composeView.parent as? DialogWindowProvider)?.window
    LaunchedEffect(dialogWindow, controlsVisible) {
        val window = dialogWindow ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controlsVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(dialogWindow) {
        onDispose {
            val window = dialogWindow ?: return@onDispose
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

internal fun initialImagePage(imageKeys: List<String>, selectedKey: String): Int =
    imageKeys.indexOf(selectedKey).coerceAtLeast(0)
