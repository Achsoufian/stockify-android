package com.soufian.stockify

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://stockifysoufian.netlify.app/"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // file chooser callback
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null

            val data = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                data == null && cameraImageUri != null -> arrayOf(cameraImageUri!!)
                data?.data != null -> arrayOf(data.data!!)
                data?.clipData != null -> {
                    val c = data.clipData!!
                    Array(c.itemCount) { i -> c.getItemAt(i).uri }
                }
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // no-op, the chooser will be opened when user taps upload again
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        // ----- WebView settings
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.loadsImagesAutomatically = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_NO_CACHE

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // ----- Pull to refresh
        swipeRefresh.setOnRefreshListener { webView.reload() }

        // ----- WebViewClient: navigation + error handling
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Allow tel:, mailto:, intent:, etc. to open externally
                return if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("intent:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: ActivityNotFoundException) {}
                    true
                } else {
                    false // load inside WebView
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
            }

            // Key bit: gracefully recover from "net::ERR_CACHE_MISS"
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                // When description contains ERR_CACHE_MISS, reload the real URL
                if (description?.contains("ERR_CACHE_MISS", ignoreCase = true) == true) {
                    view?.loadUrl(HOME_URL)
                    return
                }

                // Optional lightweight fallback page
                val safe = """
                    <!doctype html>
                    <html><head><meta name="viewport" content="width=device-width, initial-scale=1"/>
                    <style>body{font-family:sans-serif;padding:24px}</style></head>
                    <body>
                      <h3>Can’t load page</h3>
                      <p>$description</p>
                      <button onclick="location.reload()">Retry</button>
                    </body></html>
                """.trimIndent()
                view?.loadDataWithBaseURL(null, safe, "text/html", "utf-8", null)
            }
        }

        // ----- File upload chooser (gallery/camera)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback

                val accept = fileChooserParams?.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (accept.isNotBlank()) accept else "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                }

                // Prepare camera capture if permission granted
                val intents = mutableListOf(Intent.createChooser(galleryIntent, "Choose file"))

                if (ContextCompat.checkSelfPermission(this@MainActivity, CAMERA_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    try {
                        // Let Android handle the temp file; most devices return a small bitmap Uri
                        val uri = contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            android.content.ContentValues()
                        )
                        cameraImageUri = uri
                        camera.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        intents.add(camera)
                    } catch (_: Exception) { /* ignore camera if it fails to prepare */ }
                } else {
                    // ask once; user can tap upload again after granting
                    requestCameraPermission.launch(CAMERA_PERMISSION)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, galleryIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
                }
                pickFile.launch(chooser)
                return true
            }
        }

        // First load / restore
        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
            swipeRefresh.isRefreshing = true
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
