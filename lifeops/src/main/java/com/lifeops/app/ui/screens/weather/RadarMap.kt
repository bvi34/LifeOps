package com.lifeops.app.ui.screens.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lifeops.app.data.weather.TileLayer
import com.lifeops.app.util.MapCamera
import com.lifeops.app.util.MapLayout
import com.lifeops.app.util.TilePlacement
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The map itself: base tiles, an optional radar wash over them, and the pin.
 *
 * All the geometry belongs to [MapLayout]; this is only the paint. Every tile it draws comes from
 * [tileFor], which answers from memory alone — a tile that hasn't arrived simply isn't drawn, so
 * the map is always as much of the picture as is ready rather than a spinner over an empty
 * rectangle.
 */
@Composable
fun RadarMapCanvas(
    camera: MapCamera,
    onCameraChange: (MapCamera) -> Unit,
    baseLayer: TileLayer,
    radarLayer: TileLayer?,
    pin: Pair<Double, Double>?,
    widthPixels: Float,
    heightPixels: Float,
    pinColor: Color,
    /** Read inside the draw pass, so a newly-arrived tile invalidates it. */
    tileRevision: Int,
    tileFor: (String) -> ImageBitmap?,
    onTilesNeeded: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val baseTiles = MapLayout.placements(baseLayer, camera, widthPixels, heightPixels)
    val radarTiles = radarLayer
        ?.let { MapLayout.placements(it, camera, widthPixels, heightPixels) }
        .orEmpty()

    // Keyed on the URL list, so panning within tiles already on screen asks for nothing new.
    val needed = baseTiles.map { it.url } + radarTiles.map { it.url }
    LaunchedEffect(needed) { onTilesNeeded(needed) }

    // The gesture loop below deliberately outlives a frame (`pointerInput(Unit)` keeps one
    // long-running loop rather than restarting it mid-drag), so it must not close over the camera
    // it was born with. These keep it reading the current one.
    val currentCamera = rememberUpdatedState(camera)
    val emitCamera = rememberUpdatedState(onCameraChange)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    var next = currentCamera.value.panned(pan.x, pan.y)
                    if (gestureZoom != 1f) next = next.zoomedBy(gestureZoom)
                    emitCamera.value(next)
                }
            }
    ) {
        // Snapshot read: this is what redraws the map as tiles land.
        @Suppress("UNUSED_EXPRESSION")
        tileRevision

        drawRect(EMPTY_MAP_COLOR)
        baseTiles.forEach { drawTile(it, tileFor(it.url), alpha = 1f) }
        radarTiles.forEach { drawTile(it, tileFor(it.url), alpha = RADAR_ALPHA) }
        pin?.let { (latitude, longitude) ->
            val point = MapLayout.screenPointOf(latitude, longitude, camera, size.width, size.height)
            drawPin(Offset(point.x, point.y), pinColor)
        }
    }
}

private fun DrawScope.drawTile(placement: TilePlacement, image: ImageBitmap?, alpha: Float) {
    if (image == null) return
    // Ceil the size and round the origin so neighbouring tiles share an edge rather than leaving a
    // hairline of background between them at fractional zooms.
    val side = ceil(placement.size).toInt()
    drawImage(
        image = image,
        dstOffset = IntOffset(placement.left.roundToInt(), placement.top.roundToInt()),
        dstSize = IntSize(side, side),
        alpha = alpha
    )
}

/**
 * A teardrop pin whose *tip* is the coordinate — the one place on a map marker that means
 * anything. Drawn rather than asset-based so it stays crisp at any density and takes the theme's
 * colour.
 */
private fun DrawScope.drawPin(position: Offset, color: Color) {
    val headRadius = 11f * density
    val stemHeight = 30f * density
    val headCenter = Offset(position.x, position.y - stemHeight + headRadius * 0.3f)

    // A soft ground shadow anchors the tip to the map instead of floating above it.
    drawOval(
        color = Color.Black.copy(alpha = 0.25f),
        topLeft = Offset(position.x - headRadius * 0.8f, position.y - 2.5f * density),
        size = Size(headRadius * 1.6f, 5f * density)
    )

    val head = Rect(
        left = headCenter.x - headRadius,
        top = headCenter.y - headRadius,
        right = headCenter.x + headRadius,
        bottom = headCenter.y + headRadius
    )
    val body = Path().apply {
        moveTo(position.x, position.y)
        // 150° → 390° sweeps from the lower-left of the head, over the top, round to its
        // lower-right; `forceMoveTo = false` keeps the tip joined to the arc rather than starting
        // a second contour.
        arcTo(head, 150f, 240f, false)
        close()
    }
    drawPath(body, Color.White)
    drawPath(body, color, style = Stroke(width = 2f * density))
    drawCircle(color, radius = headRadius * 0.52f, center = headCenter)
}

/** The colour under a tile that hasn't arrived — a flat "map, not yet" rather than a hole. */
private val EMPTY_MAP_COLOR = Color(0xFF1B2430)

/** Enough to read the streets through a storm, enough for the storm to still be the point. */
private const val RADAR_ALPHA = 0.72f
