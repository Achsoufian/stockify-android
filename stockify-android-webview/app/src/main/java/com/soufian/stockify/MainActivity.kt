package com.soufian.stockify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://stockifysoufian.netlify.app/"
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var retryBtn: Button

    // file chooser
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            var uris: Array<Uri>? = null

            if (result.resultCode == RESULT_OK) {
                val data = result.data
                uris = when {
                    // camera captured
                    cameraImageUri != null -> arrayOf(cameraImageUri!!)
                    // single selection
                    data?.data != null -> arrayOf(data.data!!)
                    // multiple selection
                    data?.clipData != null -> {
                        val c = data.clipData!!
                        Array(c.itemCount) { i -> c.getItemAt(i).uri }
                    }
                    else -> null
                }
            }
            callback.onReceiveValue(uris ?: emptyArray())
            filePathCallback = null
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

        // --- WebView settings ---
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false

        // WebViewClient – loading + error UI
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                progress.visibility = View.GONE
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showError()
            }
        }

        // WebChromeClient – file chooser + progress
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 0..99) {
                    progress.visibility = View.VISIBLE
                    progress.progress = newProgress
                } else {
                    progress.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intents = mutableListOf<Intent>()

                // Optional camera capture (ask permission if needed)
                if (hasCameraPermission()) {
                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    val photoFile = createTempImageFile()
                    cameraImageUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intents.add(cameraIntent)
                } else {
                    requestCameraPermission()
                }

                // System picker (accept all types the site asks for)
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                    if (intents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
                    }
                }

                pickFileLauncher.launch(chooser)
                return true
            }
        }

        // pull-to-refresh
        swipeRefresh.setOnRefreshListener { webView.reload() }

        // retry button on error screen
        retryBtn.setOnClickListener {
            progress.visibility = View.VISIBLE
            loadHome()
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadHome()
        }
    }

    private fun loadHome() {
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

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1001)
        }
    }

    private fun createTempImageFile(): File {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val dir = externalCacheDir ?: cacheDir
        return File.createTempFile("IMG_${time}_", ".jpg", dir)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
