package com.faker1024.icloudsync.feature.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

@Stable
internal class ImageZoomState {
    var scale by mutableFloatStateOf(MIN_IMAGE_SCALE)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewportSize: IntSize = IntSize.Zero

    val percentage: Int
        get() = (scale * 100).roundToInt()

    val canReset: Boolean
        get() = scale > MIN_IMAGE_SCALE || offset != Offset.Zero

    fun reset() {
        scale = MIN_IMAGE_SCALE
        offset = Offset.Zero
    }

    internal fun updateViewport(size: IntSize) {
        viewportSize = size
        offset = clampImageOffset(offset, scale, viewportSize)
    }

    internal fun transform(zoomChange: Float, panChange: Offset) {
        val nextScale = (scale * zoomChange).coerceIn(MIN_IMAGE_SCALE, MAX_IMAGE_SCALE)
        scale = nextScale
        offset = if (nextScale <= MIN_IMAGE_SCALE) {
            Offset.Zero
        } else {
            clampImageOffset(offset + panChange, nextScale, viewportSize)
        }
    }

    internal fun toggleZoom(tapPosition: Offset) {
        if (scale > MIN_IMAGE_SCALE + SCALE_EPSILON) {
            reset()
            return
        }
        scale = DOUBLE_TAP_IMAGE_SCALE
        val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val focusedOffset = (center - tapPosition) * (scale - 1f)
        offset = clampImageOffset(focusedOffset, scale, viewportSize)
    }
}

@Composable
internal fun rememberImageZoomState(key: Any?): ImageZoomState = remember(key) { ImageZoomState() }

@Composable
internal fun ZoomableBitmapImage(
    bitmap: Bitmap,
    contentDescription: String?,
    state: ImageZoomState,
    modifier: Modifier = Modifier,
) {
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        state.transform(zoomChange, panChange)
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged(state::updateViewport),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                }
                .pointerInput(state) {
                    detectTapGestures(onDoubleTap = state::toggleZoom)
                }
                .transformable(transformState),
        )
    }
}

internal fun clampImageOffset(offset: Offset, scale: Float, viewportSize: IntSize): Offset {
    if (scale <= MIN_IMAGE_SCALE || viewportSize == IntSize.Zero) return Offset.Zero
    val maxX = viewportSize.width * (scale - 1f) / 2f
    val maxY = viewportSize.height * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

private const val MIN_IMAGE_SCALE = 1f
private const val MAX_IMAGE_SCALE = 6f
private const val DOUBLE_TAP_IMAGE_SCALE = 2.5f
private const val SCALE_EPSILON = 0.01f
