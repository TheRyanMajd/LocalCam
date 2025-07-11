package com.ryanmajd.localcam

import android.Manifest
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.*
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import com.pedro.rtspserver.server.ClientListener
import com.pedro.rtspserver.server.ServerClient
import fi.iki.elonen.NanoHTTPD
import androidx.compose.ui.text.font.FontWeight
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QuestionMark

import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Home

class MainActivity : ComponentActivity(), ConnectChecker, ClientListener {

    // Shared stream settings
    private var fps      by mutableStateOf(15)
    private var bitrate  by mutableStateOf(1_000_000)
    private var width    by mutableStateOf(640)
    private var height   by mutableStateOf(480)
    private var rotation by mutableStateOf(0)
    private var port     by mutableStateOf(8554)
    private val settingsFile by lazy { File(filesDir, "settings.json") }


    // RTSP instance & state
    private var rtsp: RtspServerCamera2? = null
    private var isStreaming by mutableStateOf(false)

    // Hold reference to the OpenGlView created by Compose
    private var openGlViewRef: OpenGlView? = null

    // Camera1 + MJPEG preview
    private lateinit var camera: Camera
    private val frameQueue = LinkedBlockingQueue<ByteArray>()
    private val mjpegPort = 8080
    private lateinit var httpServer: NanoHTTPD

    // Preview callback reference
    private val previewCallback = Camera.PreviewCallback { data, cam ->
        val size = cam.parameters.previewSize
        val yuv = YuvImage(data, ImageFormat.NV21, size.width, size.height, null)
        val baos = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, size.width, size.height), 70, baos)
        frameQueue.offer(baos.toByteArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            if (perms[Manifest.permission.CAMERA] == true &&
                perms[Manifest.permission.RECORD_AUDIO] == true) {
                startCamera()
                rotation = CameraHelper.getCameraOrientation(this)
            } else {
                Toast.makeText(
                    this,
                    "Camera & mic permissions required",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ))

        setContent { LocalCamApp() }
    }

    @Composable
    fun LocalCamApp() {
        val nav = rememberNavController()
        val context = LocalContext.current

        Scaffold(
            bottomBar = {
                BottomNavigation {
                    listOf("Camera", "Settings", "Connect", "About").forEach { route ->
                        // only “Camera” stays enabled while streaming
                        val enabled = !isStreaming || route == "Camera"
                        BottomNavigationItem(
                            selected = nav.currentBackStackEntry?.destination?.route == route,
                            onClick  = {
                                if (enabled) {
                                    nav.navigate(route) {
                                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                } else {
                                    Toast.makeText(context, "Stop streaming to switch pages", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            },
                            icon = {
                                     when (route) {
                                          "Camera"           -> Icon(Icons.Default.Camera,        contentDescription = "Camera")
                                          "Settings"         -> Icon(Icons.Default.Settings,      contentDescription = "Settings")
                                          "Connect"   -> Icon(Icons.Default.QuestionMark,  contentDescription = "How to Connect")
                                          "About"            -> Icon(Icons.Default.Info,          contentDescription = "About")
                                          else               -> Icon(Icons.Default.Info,          contentDescription = route)
                                        }
                                  },
                            label    = { Text(route) },
                            enabled  = enabled
                        )
                    }
                }
            }
        ) { pad ->
            NavHost(nav, startDestination = "Camera", Modifier.padding(pad)) {
                composable("Camera")       { CameraPage() }
                composable("Settings")     { SettingsPage() }
                composable("Connect"){ ConnectPage() }
                composable("About")        { AboutPage() }
            }
        }
    }


    @Composable
    private fun CameraPage() {
        val ctx = LocalContext.current
        var endpoint by remember { mutableStateOf("rtsp://${getLocalIpAddress()}:$port/live") }

        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AndroidView(
                factory = { c ->
                    OpenGlView(c).also { gl ->
                        openGlViewRef = gl
                    }
                },
                modifier = Modifier.weight(1f)
            )

            Text(endpoint, Modifier.padding(8.dp))

            Button(
                onClick = {
                    if (isStreaming) {
                        // STOP
                        rtsp?.stopStream()
                        rtsp = null
                        isStreaming = false
                        safeResumeCamera()
                    } else {
                        // START
                        safePauseCamera()
                        val gl = openGlViewRef ?: run {
                            Toast.makeText(ctx, "Preview not ready", Toast.LENGTH_SHORT).show()
                            safeResumeCamera()
                            return@Button
                        }
                        rtsp = RtspServerCamera2(
                            openGlView     = gl,
                            connectChecker = this@MainActivity,
                            port           = port
                        ).apply {
                            streamClient.setClientListener(this@MainActivity)
                        }
                        if (rtsp!!.prepareVideo(width, height, fps, bitrate, rotation)
                            && rtsp!!.prepareAudio()) {
                            rtsp!!.startStream()
                            endpoint = "rtsp://${getLocalIpAddress()}:$port/live"
                            isStreaming = true
                            Toast.makeText(ctx, "Streaming at $endpoint", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(ctx, "Stream start failed", Toast.LENGTH_LONG).show()
                            safeResumeCamera()
                        }

                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isStreaming) "Stop" else "Start")
            }
        }
    }

    @Composable
    private fun SettingsPage() {
        val savedJson = remember {
            settingsFile.takeIf { it.exists() }?.readText()
        }
        val saved = remember {
            savedJson?.let { JSONObject(it) } ?: JSONObject()
        }
        val ctx = LocalContext.current

        // local text states, editable freely
        var widthText     by remember { mutableStateOf(saved.optInt("width", width).toString()) }
        var heightText    by remember { mutableStateOf(saved.optInt("height", height).toString()) }
        var fpsText       by remember { mutableStateOf(saved.optInt("fps", fps).toString()) }
        var bitrateText   by remember { mutableStateOf((saved.optInt("bitrate", bitrate)/1000).toString()) }
        var rotationText  by remember { mutableStateOf(saved.optInt("rotation", rotation).toString()) }
        var portText      by remember { mutableStateOf(saved.optInt("port", port).toString()) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = widthText,
                onValueChange = { widthText = it },
                label = { Text("Width (160–1920)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = heightText,
                onValueChange = { heightText = it },
                label = { Text("Height (120–1080)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = fpsText,
                onValueChange = { fpsText = it },
                label = { Text("FPS (1–60)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                "❗High bitrates may choke your LAN—2500 kbps max recommended",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.error
            )
            OutlinedTextField(
                value = bitrateText,
                onValueChange = { bitrateText = it },
                label = { Text("Bitrate (100–5000 kbps)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = rotationText,
                onValueChange = { rotationText = it },
                label = { Text("Rotation° (0–270)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("RTSP Port (1024–65535)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Button(onClick = {
                // only apply if valid range
                widthText.toIntOrNull()?.takeIf { it in 160..1920 }?.let { width = it }
                heightText.toIntOrNull()?.takeIf { it in 120..1080 }?.let { height = it }
                fpsText.toIntOrNull()?.takeIf { it in 1..60 }?.let { fps = it }
                bitrateText.toIntOrNull()
                    ?.takeIf { it in 100..5000 }
                    ?.let { bitrate = it * 1000 }
                rotationText.toIntOrNull()?.takeIf { it in 0..270 }?.let { rotation = it }
                portText.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { port = it }

                if (isStreaming) {
                    rtsp?.stopPreview()
                    rtsp?.stopStream()
                    rtsp = null
                    isStreaming = false
                    safeResumeCamera()
                }
                val out = JSONObject().apply {
                    put("width", width)
                    put("height", height)
                    put("fps", fps)
                    put("bitrate", bitrate)
                    put("rotation", rotation)
                    put("port", port)
                }
                settingsFile.writeText(out.toString())
                Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
            }) {
                Text("Save Settings")
            }
        }
    }


    @Composable
    private fun ConnectPage() {
        val url = "rtsp://${getLocalIpAddress()}:$port/live"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text("How to Connect", style = MaterialTheme.typography.h5)
            }
            Text(
                "Your phone is now acting as an IP camera. To view the video stream, use the address below in a compatible app on the same Wi-Fi network."
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Your Camera's Live Feed URL", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            }
            Text(
                "⚠️ Only one viewer supported at a time.\n⚠️ Audio is automatically picked up.",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.error
            )
            Text(url, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.primary)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Option 1: VLC Media Player", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            }
            Text(
                """
            1. Open VLC → Media → Open Network Stream…
            2. Paste URL: $url
            3. Click Play.
            """.trimIndent()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Option 2: ffplay (Advanced)", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            }
            Text("Install FFmpeg, then run:\nffplay $url")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Option 3: Home Assistant", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            }
            Text(
                """
            camera:
              - platform: generic
                name: "Android IP Camera"
                stream_source: "$url"
                still_image_url: "${url}?action=snapshot"
            """.trimIndent()
            )
        }
    }
    @Composable
    private fun AboutPage() {
        val uriHandler = LocalUriHandler.current

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                elevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Ryan Majd", style = MaterialTheme.typography.h6)
                    Text("Software Engineer", style = MaterialTheme.typography.body2)

                    Spacer(Modifier.height(6.dp))

                    Text("LocalCam", style = MaterialTheme.typography.h6)
                    Text("Version: 1.0.0", style = MaterialTheme.typography.body2)
                }
            }

            Text("What is LocalCam?", style = MaterialTheme.typography.h5)
            Text(
                "A vibe-coded Android app to breathe life into decade-old phones, turning them into RTSP IP cameras over LAN."
            )

            Text("Highlights", style = MaterialTheme.typography.h6)
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Built in Kotlin + Jetpack Compose", style = MaterialTheme.typography.body1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VideoCall, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Hardware-accelerated H.264 + AAC streaming", style = MaterialTheme.typography.body1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Zero-install: Start → hit Start → open rtsp://<ip>:$port/live", style = MaterialTheme.typography.body1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Auto IP discovery & minimal latency in ffplay", style = MaterialTheme.typography.body1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Settings auto-saved in JSON: no re-input needed", style = MaterialTheme.typography.body1)
                }
            }

            Button(
                onClick = { uriHandler.openUri("https://ryanmajd.com") },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("My Website")
            }

            Button(
                onClick = {
                    uriHandler.openUri("https://www.linkedin.com/in/ryanmajd")
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("LinkedIn")
            }
        }
    }



    @Suppress("DEPRECATION")
    private fun startCamera() {
        camera = Camera.open().apply {
            parameters = parameters.apply {
                previewFormat = ImageFormat.NV21
                setPreviewSize(320, 240)
            }
            setPreviewCallback(previewCallback)
            startPreview()
        }
    }

    // wrap pause in a safe method
    @Suppress("DEPRECATION")
    private fun safePauseCamera() {
        try {
            camera.setPreviewCallback(null)
            camera.stopPreview()
        } catch (e: Exception) {
            Log.e("LocalCam", "pauseCamera failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun safeResumeCamera() {
        try {
            camera.setPreviewCallback(previewCallback)
            camera.startPreview()
        } catch (e: Exception) {
            Log.e("LocalCam", "resumeCamera failed", e)
        }
    }

    private fun getLocalIpAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .firstNotNullOfOrNull { nif ->
                nif.inetAddresses.toList()
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            }?.hostAddress

    // ConnectChecker
    override fun onConnectionSuccess()              = show("RTSP ready")
    override fun onConnectionFailed(reason: String) = show("Error: $reason")
    override fun onConnectionStarted(url: String)   = Unit
    override fun onDisconnect()                     = show("Viewer left")
    override fun onAuthError()                      = show("Auth error")
    override fun onAuthSuccess()                    = show("Auth success")

    // ClientListener
    override fun onClientConnected(client: ServerClient) = show("Viewer connected")
    override fun onClientDisconnected(client: ServerClient) = Unit
    override fun onClientNewBitrate(bitrate: Long, c: ServerClient) = Unit

    private fun show(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        camera.stopPreview(); camera.release()
        httpServer.stop()
        rtsp?.stopStream()
        super.onDestroy()
    }
}
