package com.nextgis.mobiledemo

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.nextgis.maplib.*
import java.lang.ref.WeakReference

class MainActivity : Activity(), GestureDelegate {

    private var mapRef: WeakReference<MapDocument>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        API.init(this@MainActivity)
        mapRef = WeakReference<MapDocument>(API.getMap("main"))

        val map = mapRef?.get()
        if(map != null) {
            val activityManager = this@MainActivity.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            // For low memory devices we create tiles bigger, so we get less tiles and less memory load
            var reduceFactor = 1.0
            val totalRam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                memoryInfo.totalMem / (1024 * 1024)
            } else {
                512
            }

            if(totalRam < 1024) {
                reduceFactor = 2.0
            }

            val options = mapOf(
                "ZOOM_INCREMENT" to "-1", // Add extra to zoom level corresponding to scale
                "VIEWPORT_REDUCE_FACTOR" to reduceFactor.toString() // Reduce viewport width and height to decrease memory usage
            )

            map.setOptions(options)
            map.setExtentLimits(-20037508.34,-20037508.34,20037508.34,20037508.34)

            if(map.layerCount == 0) { // Map is just created
                addPointsTo(map)
                addOSMTo(map)

                map.save()
            }

            val mapView = findViewById<MapView>(R.id.mapView)
            if(mapView != null) {
                mapView.setMap(map)
            mapView.registerGestureRecognizers(this)
            }

            mapView.freeze = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        val map = mapRef?.get()
        if(map != null) {
            map.save()
            map.close()
        }
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)

        val mapView = findViewById<MapView>(R.id.mapView)
        if(mapView != null && outState != null) {
            outState.putDouble("map_scale", mapView.mapScale)
            val mapCenter = mapView.mapCenter
            outState.putDouble("map_center_x", mapCenter.x)
            outState.putDouble("map_center_y", mapCenter.y)
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        val mapView = findViewById<MapView>(R.id.mapView)
        if(mapView != null && savedInstanceState != null) {
            val mapCenterX = savedInstanceState.getDouble("map_center_x", 0.0)
            val mapCenterY = savedInstanceState.getDouble("map_center_y", 0.0)

            mapView.mapScale = savedInstanceState.getDouble("map_scale", 0.0000015)
            mapView.mapCenter = Point(mapCenterX, mapCenterY)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean = when (item?.itemId) {
        R.id.action_info -> {
            val builder = AlertDialog.Builder(this@MainActivity)
            builder.setTitle(R.string.action_info)
            builder.setMessage("NextGIS Demo application\n" +
                    "SDK version: ${API.versionString()}\n" +
                    "GDAL: ${API.versionString("gdal")}\n" +
                    "GEOS: ${API.versionString("geos")}\n" +
                    "PROJ.4: ${API.versionString("proj")}\n" +
                    "Sqlite: ${API.versionString("sqlite")}\n" +
                    "TIFF ${API.versionString("tiff")} [GeoTIFF ${API.versionString("geotiff")}]\n" +
                    "JPEG: ${API.versionString("jpeg")}\n" +
                    "PNG: ${API.versionString("png")}")
            builder.setCancelable(false)
            builder.setPositiveButton("Close", null)
            val dialog: AlertDialog = builder.create()
            dialog.show()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    private fun addOSMTo(map: MapDocument) {
        val dataDir = API.getDataDirectory()
        if(dataDir != null) {
            val bbox = Envelope(-20037508.34, 20037508.34, -20037508.34, 20037508.34)
            val baseMap = dataDir.createTMS("osm.wconn", "http://tile.openstreetmap.org/{z}/{x}/{y}.png",
                    3857, 0, 18, bbox, bbox, 14)

            map.addLayer("OSM", baseMap!!)
        }
    }

    fun addPointsTo(map: MapDocument) {
        // Get or create data store
        val dataStore = API.getStore("store")
        if(dataStore != null) {
            // Create points feature class

            val options = mapOf(
                "CREATE_OVERVIEWS" to "ON",
                "ZOOM_LEVELS" to "2,3,4,5,6,7,8,9,10,11,12,13,14"
            )

            val fields = listOf(
                Field("long", "long", Field.Type.REAL),
                Field("lat", "lat", Field.Type.REAL),
                Field("datetime", "datetime", Field.Type.DATE, "CURRENT_TIMESTAMP"),
                Field("name", "name", Field.Type.STRING)
            )

            val pointsFC = dataStore.createFeatureClass("points", Geometry.Type.POINT, fields, options)
            if(pointsFC != null) {

                data class PtCoord(val name: String, val x: Double, val y: Double)

                // Add geodata to points feature class from https://en.wikipedia.org
                val coordinates = listOf(
                    PtCoord("Moscow", 37.616667, 55.75),
                    PtCoord("London", -0.1275, 51.507222),
                    PtCoord("Washington", -77.016389, 38.904722),
                    PtCoord("Beijing", 116.383333, 39.916667)
                )

                val coordTransform = CoordinateTransformation.new(4326, 3857)

                for(coordinate in coordinates) {
                    val feature = pointsFC.createFeature()
                    if(feature != null) {
                        val geom = feature.createGeometry() as? GeoPoint
                        if(geom != null) {
                            val point = Point(coordinate.x, coordinate.y)
                            val transformPoint = coordTransform.transform(point)
                            geom.setCoordinates(transformPoint)
                            feature.geometry = geom
                            feature.setField(0, coordinate.x)
                            feature.setField(1, coordinate.y)
                            feature.setField(3, coordinate.name)
                            pointsFC.insertFeature(feature)
                        }
                    }
                }

                // Create layer from points feature class
                val pointsLayer = map.addLayer("Points", pointsFC)
                if(pointsLayer != null) {

                    // Set layer style
                    pointsLayer.styleName = "pointsLayer"
                    val style = pointsLayer.style
                    style.setString("color", colorToHexString(Color.rgb(0, 190,120)))
                    style.setDouble("size", 8.0)
                    style.setInteger("type", 6) // Star symbol

                    pointsLayer.style = style
                }
            }
        }
    }

    private fun getPointsFeatureClass() : FeatureClass? {
        return mapRef?.get()?.getLayer(0)?.dataSource as? FeatureClass
    }

    @Suppress("UNUSED_PARAMETER")
    fun onZoomIn(view: View) {
        val mapView = findViewById<MapView>(R.id.mapView)
        mapView.zoomIn()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onZoomOut(view: View) {
        val mapView = findViewById<MapView>(R.id.mapView)
        mapView.zoomOut()
    }
}
