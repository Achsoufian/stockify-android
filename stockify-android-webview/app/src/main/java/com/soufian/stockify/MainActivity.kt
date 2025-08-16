package com.soufian.stockify

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://stockifysoufian.netlify.app/"
        private const val CAMERA_REQ_CODE = 1002
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var retryBtn: Button

    // File chooser state
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // Launcher for chooser result (gallery/camera)
    private val chooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null

            if (cb == null) return@registerForActivityResult

            val data = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                // camera case (we wrote to EXTRA_OUTPUT)
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                // single picked file
                data?.data != null -> arrayOf(data.data!!)
                // multiple picked files
                data?.clipData != null -> {
                    val c = data.clipData!!
                    Array(c.itemCount) { i -> c.getItemAt(i).uri }
                }
                else -> null
            }

            cb.onReceiveValue(uris)
            cameraImageUri = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        retryBtn = findViewById(R.id.btnRetry)

        // --- WebView settings (keep it simple & reliable)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Pull-to-refresh (only when WebView is scrolled to top)
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener { webView.reload() }

        // WebViewClient: page lifecycle + error UI
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    // open non-http(s) intents externally
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: ActivityNotFoundException) {}
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                progress.visibility = View.GONE
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showError()
            }

            // Legacy API (covers some devices)
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                showError()
            }
        }

        // WebChromeClient: file chooser (gallery + camera) and basic progress
        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Clean any previous callback
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                // Camera intent (only if we have permission)
                val initialIntents = mutableListOf<Intent>()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    try {
                        val photo = createTempImageFile()
                        cameraImageUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            photo
                        )
                        cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        initialIntents.add(cam)
                    } catch (_: Exception) {
                        cameraImageUri = null
                    }
                } else {
                    // Ask once; user can tap upload again after granting
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQ_CODE)
                }

                // System file picker (accept site hint if provided)
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    // Pass through acceptTypes if any
                    fileChooserParams?.acceptTypes?.let { types ->
                        if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types)
                    }
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                    if (initialIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
                    }
                }

                chooserLauncher.launch(chooser)
                return true
            }
        }

        // Retry button
        retryBtn.setOnClickListener { loadHome() }

        // First load
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadHome()
        }
    }

    private fun loadHome() {
        progress.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(HOME_URL)
    }

    private fun showError() {
        swipeRefresh.isRefreshing = false
        progress.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        errorView.visibility = View.VISIBLE
    }

    private fun createTempImageFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(externalCacheDir ?: cacheDir, "IMG_${ts}.jpg")
        file.createNewFile()
        return file
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
