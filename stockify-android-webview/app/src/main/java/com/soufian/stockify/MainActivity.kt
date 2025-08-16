package com.soufian.stockify

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private val HOME_URL = "https://stockifysoufian.netlify.app/"

    // file chooser launcher
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback
            fileCallback = null

            if (result.resultCode != Activity.RESULT_OK || callback == null) {
                callback?.onReceiveValue(null)
                return@registerForActivityResult
            }

            val data = result.data
            val results: Array<Uri>? = when {
                // result from camera
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                // result from picker
                data != null -> {
                    val clip: ClipData? = data.clipData
                    when {
                        clip != null && clip.itemCount > 0 -> {
                            Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                        }
                        data.data != null -> arrayOf(data.data!!)
                        else -> null
                    }
                }
                else -> null
            }

            callback.onReceiveValue(results)
            cameraImageUri = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        setupWebView()
        webView.loadUrl(HOME_URL)

        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
    }

    private fun setupWebView() = with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        useWideViewPort = true
        loadWithOverviewMode = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                swipeRefresh.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // keep a reference
                fileCallback = filePathCallback

                // Build a generic OPEN_DOCUMENT (or GET_CONTENT) picker
                val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"  // always allow; hint with EXTRA_MIME_TYPES
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
                            "application/vnd.ms-excel", // .xls
                            "text/csv",
                            "image/*"
                        )
                    )
                }

                // Optional: camera capture intent (image/jpeg)
                val cameraIntent = makeCameraIntent()

                // Create chooser; include camera if available
                val initialIntents = arrayListOf<Intent>()
                cameraIntent?.let { initialIntents.add(it) }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, docIntent)
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
                }

                return try {
                    swipeRefresh.isRefreshing = true
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (e: ActivityNotFoundException) {
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(
                        this@MainActivity,
                        "No file picker app found.",
                        Toast.LENGTH_SHORT
                    ).show()
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }
    }

    private fun makeCameraIntent(): Intent? {
        // Check camera permission quickly; if not granted, skip camera in chooser
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val photoFile = try {
            createTempImageFile()
        } catch (e: Exception) {
            null
        } ?: return null

        cameraImageUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            photoFile
        )

        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun createTempImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = cacheDir // app cache; allowed by FileProvider <cache-path/>
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
