package com.soufian.stockify

import android.Manifest
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
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://stockifysoufian.netlify.app/"
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var retryBtn: Button

    // file upload state
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // chooser result launcher
    private val chooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            var uris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val picked = data?.data
                uris = when {
                    picked != null -> arrayOf(picked)
                    cameraImageUri != null -> arrayOf(cameraImageUri!!)
                    else -> null
                }
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        retryBtn = findViewById(R.id.btnRetry)

        // --- WebView settings
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // if you ever embed http content under https
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // pull-to-refresh should only trigger when WebView is scrolled to top
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener { webView.reload() }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
                progress.visibility = View.GONE
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) showError()
            }
        }

        // ---- File chooser + camera
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // ensure previous callbacks are cleaned
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                // camera permission (needed on some devices)
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 1002)
                    // tell the page to retry once user grants
                }

                val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { intent ->
                    val photoFile = createTempImageFile()
                    cameraImageUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    intent.addFlags(
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                val contentTypes = arrayOf("image/*", "application/pdf")
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, contentTypes)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                }

                chooserLauncher.launch(chooser)
                return true
            }
        }

        retryBtn.setOnClickListener { loadHome() }
        loadHome()
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

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun createTempImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFileName = "IMG_${timeStamp}.jpg"
        return File(cacheDir, imageFileName).apply { createNewFile() }
    }
}
