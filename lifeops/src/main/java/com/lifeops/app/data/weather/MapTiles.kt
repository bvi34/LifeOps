package com.lifeops.app.data.weather

import com.lifeops.app.util.TileMath
import java.util.Locale

/**
 * Where the radar map's pictures come from, and nothing else — building a URL for one 256-pixel
 * square is the whole job. Pure (no I/O, no Android), so which server is asked for what stays a
 * testable decision rather than something buried in a composable.
 *
 * Two request shapes cover every raster service worth pointing at:
 *
 * - **XYZ** — the ordinary `{z}/{x}/{y}.png` slippy-map convention, used by the base map and by
 *   NEXRAD mosaic caches.
 * - **WMS** — an OGC `GetMap` call for an explicit bounding box, which is how NOAA's own GeoServer
 *   publishes radar. [TileMath.tileBoundsMeters] turns the tile into the EPSG:3857 box it wants,
 *   so a WMS layer slots into the same tile grid as an XYZ one and the map never has to care.
 *
 * Everything here is keyless and public. The base map is the only non-NWS service LifeOps talks to,
 * and it is fetched only while the radar screen is actually open.
 */
sealed interface TileLayer {

    /** Short name for the layer switcher. */
    val label: String

    /** Credit line the map must display while this layer is drawn. */
    val attribution: String

    /** Deepest zoom this service has imagery for; past it the map reuses the last good level. */
    val maxZoom: Int

    /** The URL of one tile, or null when this layer has nothing at that coordinate. */
    fun urlFor(x: Int, y: Int, zoom: Int): String?

    /** An `{z}/{x}/{y}` raster cache. [subdomains], when non-empty, round-robins `{s}`. */
    data class Xyz(
        override val label: String,
        override val attribution: String,
        override val maxZoom: Int,
        private val template: String,
        private val subdomains: List<String> = emptyList()
    ) : TileLayer {
        override fun urlFor(x: Int, y: Int, zoom: Int): String? {
            if (!TileMath.isRowInRange(y, zoom)) return null
            val column = TileMath.wrapColumn(x, zoom)
            var url = template
                .replace("{z}", zoom.toString())
                .replace("{x}", column.toString())
                .replace("{y}", y.toString())
            if (subdomains.isNotEmpty()) {
                // Deterministic rather than random so the same tile keeps the same URL — and so
                // the tile cache keeps working across pans.
                url = url.replace("{s}", subdomains[Math.floorMod(column + y, subdomains.size)])
            }
            return url
        }
    }

    /** An OGC WMS endpoint asked for one tile-shaped bounding box at a time. */
    data class Wms(
        override val label: String,
        override val attribution: String,
        override val maxZoom: Int,
        private val endpoint: String,
        private val layers: String,
        private val version: String = "1.3.0"
    ) : TileLayer {
        override fun urlFor(x: Int, y: Int, zoom: Int): String? {
            if (!TileMath.isRowInRange(y, zoom)) return null
            val b = TileMath.tileBoundsMeters(TileMath.wrapColumn(x, zoom), y, zoom)
            val bbox = b.joinToString(",") { String.format(Locale.US, "%.6f", it) }
            // WMS 1.3.0 renamed SRS to CRS; both spellings are sent so one endpoint's strictness
            // about the version it advertises can't blank the layer.
            val axis = if (version == "1.3.0") "crs" else "srs"
            val separator = if ('?' in endpoint) "&" else "?"
            return endpoint + separator + listOf(
                "service=WMS",
                "version=$version",
                "request=GetMap",
                "layers=$layers",
                "styles=",
                "format=image/png",
                "transparent=true",
                "$axis=EPSG:3857",
                "width=${TileMath.TILE_SIZE_PX}",
                "height=${TileMath.TILE_SIZE_PX}",
                "bbox=$bbox"
            ).joinToString("&")
        }
    }
}

/** The base map the pin and the radar are drawn over. */
object BaseMapLayer {
    val STREETS: TileLayer = TileLayer.Xyz(
        label = "Streets",
        attribution = "© OpenStreetMap contributors",
        maxZoom = 18,
        template = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    )
}

/**
 * The radar products the map can overlay. Two of them on purpose: NOAA's own GeoServer is the
 * authoritative source and matches the rest of the weather stack, but it is a single endpoint that
 * can be down for maintenance — and a radar screen that shows nothing is worse than one showing a
 * mosaic from somewhere else. The switcher in the top bar is the fallback.
 */
enum class RadarProduct(val layer: TileLayer) {

    /** NOAA GeoServer: quality-controlled CONUS base reflectivity — the national radar mosaic. */
    NWS_REFLECTIVITY(
        TileLayer.Wms(
            label = "NWS reflectivity",
            attribution = "Radar: NOAA/NWS",
            maxZoom = 12,
            endpoint = "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows",
            layers = "conus_bref_qcd"
        )
    ),

    /** Iowa State Mesonet's NEXRAD N0Q mosaic — an independent cache of the same radar network. */
    NEXRAD_MOSAIC(
        TileLayer.Xyz(
            label = "NEXRAD mosaic",
            attribution = "Radar: Iowa State Mesonet",
            maxZoom = 10,
            template = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/{z}/{x}/{y}.png"
        )
    );

    val label: String get() = layer.label

    companion object {
        /** Resolve a persisted name back to a product, falling back to the authoritative one. */
        fun from(name: String?): RadarProduct =
            entries.firstOrNull { it.name == name } ?: NWS_REFLECTIVITY
    }
}

/**
 * The reflectivity ramp, weakest to strongest, for the legend under the map. Radar colours are
 * conventional rather than decorative — green is drizzle, yellow is a downpour, magenta is hail —
 * so the legend is what makes the overlay readable instead of merely colourful.
 */
data class ReflectivityStop(val label: String, val colorArgb: Long)

val REFLECTIVITY_SCALE = listOf(
    ReflectivityStop("Light", 0xFF00C853),
    ReflectivityStop("", 0xFF64DD17),
    ReflectivityStop("Moderate", 0xFFFFD600),
    ReflectivityStop("", 0xFFFF9100),
    ReflectivityStop("Heavy", 0xFFDD2C00),
    ReflectivityStop("Hail", 0xFFAA00FF)
)
