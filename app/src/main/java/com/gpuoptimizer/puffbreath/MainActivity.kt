package com.gpuoptimizer.puffbreath

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.presagetech.smartspectra.CameraSelection
import com.presagetech.smartspectra.ProcessingStatus
import com.presagetech.smartspectra.SmartSpectraConfig
import com.presagetech.smartspectra.SmartSpectraSdk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Puff inside a WebView, steered by breathing measured with SmartSpectra.
 *
 * Data flows one way only: SmartSpectra -> evaluateJavascript -> window.PuffBreath.receive().
 * There is deliberately no addJavascriptInterface, so the page (and any ad inside it)
 * has no way to call native code.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val GAME_URL = "https://puff-game.netlify.app/"
        const val GAME_HOST = "puff-game.netlify.app"
    }

    private val sdk by lazy { SmartSpectraSdk.shared }

    private lateinit var webView: WebView
    private lateinit var hintLabel: TextView

    private var pageReady = false
    private var startInFlight = false
    private var lastTraceTimestamp = Long.MIN_VALUE
    private var lastStableRate: Double? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVitals() else showHint("Camera is off, so tap to play.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Breathing replaces tapping, so keep the screen from timing out mid-game.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sdk.config.apiKey = BuildConfig.SMARTSPECTRA_API_KEY
        sdk.config.imageOutputEnabled = false
        sdk.config.requestedMetrics =
            SmartSpectraConfig.breathingMetrics + SmartSpectraConfig.cardioMetrics
        // useCamera() throws if the shared SDK is still busy from a previous session.
        runCatching { sdk.useCamera(CameraSelection.Front) }

        buildUi()
        bindSdk()
        webView.loadUrl(GAME_URL)

        when {
            BuildConfig.SMARTSPECTRA_API_KEY.isBlank() ->
                showHint("This build has no SmartSpectra API key, so tap to play.")
            !hasCameraPermission() -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Permission is only requested once, in onCreate. Requesting here would loop,
        // because the permission dialog itself pauses and resumes the activity.
        if (hasCameraPermission()) startVitals()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        lifecycleScope.launch {
            when (sdk.processingStatus.value) {
                ProcessingStatus.RUNNING,
                ProcessingStatus.STARTING,
                ProcessingStatus.STOPPING -> runCatching { sdk.stop() }
                else -> Unit
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startVitals() {
        if (startInFlight || !hasCameraPermission() || BuildConfig.SMARTSPECTRA_API_KEY.isBlank()) return
        val status = sdk.processingStatus.value
        if (status == ProcessingStatus.RUNNING || status == ProcessingStatus.STARTING) return

        startInFlight = true
        lifecycleScope.launch {
            try {
                // A quick pause/resume can catch the previous session mid-shutdown.
                while (sdk.processingStatus.value == ProcessingStatus.STOPPING) delay(50)
                lastTraceTimestamp = Long.MIN_VALUE
                lastStableRate = null
                runInGame("window.PuffBreath&&window.PuffBreath.reset()")
                showHint("Prop the phone up so your face and upper chest are in view. Breathe normally while it tunes in (about 30 seconds).")
                runCatching { sdk.start() }
                    .onFailure { showHint("Couldn't start breath tracking: ${it.message ?: "unknown error"}") }
            } finally {
                startInFlight = false
            }
        }
    }

    private fun bindSdk() {
        sdk.validationStatus.observe(this) { status ->
            if (status != null && status.code.name != "OK") showHint(status.hint)
        }
        sdk.error.observe(this) { error ->
            if (error != null) showHint("Breath tracking error: ${error.message}")
        }
        sdk.metrics.observe(this) { metrics ->
            if (metrics == null || !metrics.hasBreathing()) return@observe

            // Each payload holds only samples that are new since the last one,
            // but keep the timestamp check so nothing is sent twice.
            val trace = JSONArray()
            var stable = false
            for (sample in metrics.breathing.upperTraceList) {
                if (sample.timestamp > lastTraceTimestamp && sample.value.isFinite()) {
                    lastTraceTimestamp = sample.timestamp
                    trace.put(sample.value.toDouble())
                    stable = sample.stable
                }
            }
            if (trace.length() == 0) return@observe
            if (stable) hideHint()

            // Rates arrive only now and then; keep the last stable one.
            metrics.breathing.rateList
                .lastOrNull { it.timestamp > 0 && it.stable }
                ?.let { lastStableRate = it.value.toDouble() }
            val pulse = if (metrics.hasCardio()) {
                metrics.cardio.pulseRateList
                    .lastOrNull { it.timestamp > 0 && it.stable }
                    ?.value?.toDouble()
            } else {
                null
            }

            // Only numbers, booleans and null go into the page; no SDK strings.
            val payload = JSONObject()
                .put("trace", trace)
                .put("stable", stable)
                .put("rate", lastStableRate ?: JSONObject.NULL)
                .put("pulse", pulse ?: JSONObject.NULL)
            runInGame("window.PuffBreath&&window.PuffBreath.receive($payload)")
        }
    }

    /** Runs a script in the page, but only while the page is the real game. */
    private fun runInGame(script: String) {
        if (pageReady && isGameUrl(webView.url)) webView.evaluateJavascript(script, null)
    }

    private fun isGameUrl(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        return uri.scheme == "https" && uri.host == GAME_HOST
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true // the game is JavaScript
            domStorageEnabled = true // lets Puff keep scores and names in localStorage
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                if (isGameUrl(target.toString())) return false
                // Anything off-site (ad clicks, links) opens in the browser, never in the app.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, target))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageReady = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = isGameUrl(url)
                runInGame("window.PuffBreath&&window.PuffBreath.setInApp(true)")
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            // The page never gets the camera or microphone; SmartSpectra owns the camera.
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(this).also { configureWebView(it) }
        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Small self-view so players can keep their face and upper chest in frame.
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Camera view for breath tracking"
        }
        sdk.config.previewSurfaceProvider = preview.surfaceProvider
        root.addView(
            preview,
            FrameLayout.LayoutParams(dp(96), dp(128), Gravity.TOP or Gravity.END).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            },
        )

        hintLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        root.addView(
            hintLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        // Keep everything clear of the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun showHint(text: String) {
        hintLabel.text = text
        hintLabel.visibility = View.VISIBLE
    }

    private fun hideHint() {
        hintLabel.visibility = View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
