package com.soufian.stockify

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout

    private val HOME_URL = "https://stockifysoufian.netlify.app/"

    // Web getUserMedia camera permission
    private var pendingWebPermission: PermissionRequest? = null
    private val CAMERA_REQ = 1234

    // File upload callback
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Robust chooser result (handles single/multiple/cancel safely)
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val cb = fileChooserCallback
            fileChooserCallback = null

            if (cb == null) return@registerForActivityResult

            if (result.resultCode != RESULT_OK) {
                cb.onReceiveValue(emptyArray())
                return@registerForActivityResult
            }

            val data = result.data
            val uris: Array<Uri> = when {
                data?.clipData != null -> {
                    val c = data.clipData!!
                    Array(c.itemCount) { i -> c.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> emptyArray()
            }
            cb.onReceiveValue(uris)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipe = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        swipe.setOnRefreshListener { webView.reload() }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                swipe.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // Camera for getUserMedia (barcode scanner, etc.)
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        } else {
                            pendingWebPermission = request
                            ActivityCompat.requestPermissions(
                                this@MainActivity,
                                arrayOf(Manifest.permission.CAMERA),
                                CAMERA_REQ
                            )
                        }
                    } else {
                        request.grant(request.resources)
                    }
                }
            }

            // File uploads from <input type="file">
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // store the callback
                this@MainActivity.fileChooserCallback = filePathCallback

                // Build a robust ACTION_GET_CONTENT intent
                val acceptTypes = fileChooserParams.acceptTypes
                val allowMultiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (acceptTypes.isNotEmpty() && acceptTypes[0].isNotBlank())
                        acceptTypes[0]
                    else
                        "*/*"
                    if (acceptTypes.isNotEmpty()) {
                        putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.filter { it.isNotBlank() }.toTypedArray())
                    }
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                }

                try {
                    fileChooserLauncher.launch(chooser)
                } catch (e: ActivityNotFoundException) {
                    // Fallback: try OPEN_DOCUMENT
                    try {
                        val openDoc = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                        }
                        fileChooserLauncher.launch(openDoc)
                    } catch (_: Exception) {
                        // Last resort: cancel cleanly to avoid crashes
                        this@MainActivity.fileChooserCallback?.onReceiveValue(emptyArray())
                        this@MainActivity.fileChooserCallback = null
                    }
                }
                return true
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }

        // Ask once so camera works immediately inside WebView
        ensureCameraPermission()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQ
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQ) {
            val granted =
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingWebPermission?.let { req ->
                if (granted) {
                    req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    req.deny()
                    // Optional: guide user to Settings if permanently denied
                    // startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                pendingWebPermission = null
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
