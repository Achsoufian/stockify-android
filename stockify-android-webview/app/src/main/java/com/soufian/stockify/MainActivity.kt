package com.soufian.stockify

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // For <input type="file"> (with optional camera capture)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uris: Array<Uri> = when {
                result.resultCode != Activity.RESULT_OK -> emptyArray()
                // Camera capture (no data intent, just our saved uri)
                data == null && cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
                // Multiple selection
                data?.clipData != null -> {
                    val c = data.clipData!!
                    Array(c.itemCount) { i -> c.getItemAt(i).uri }
                }
                // Single selection
                data?.data != null -> arrayOf(data.data!!)
                else -> emptyArray()
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
            cameraPhotoUri = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // Keep navigation inside the WebView
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) view?.loadUrl(url)
                return true
            }
        }

        // Grant getUserMedia (camera/mic) and handle file chooser
        webView.webChromeClient = object : WebChromeClient() {

            // IMPORTANT: allow the page to access camera via JS getUserMedia
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val allowedOrigin = "https://stockifysoufian.netlify.app"
                if (request.origin.toString() == allowedOrigin) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }

            // File chooser with optional camera capture
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback

                // Camera intent
                val cameraIntents = mutableListOf<Intent>()
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                val photoFile = createTempImageFile()
                cameraPhotoUri = photoFile?.let {
                    FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        it
                    )
                }
                if (cameraPhotoUri != null) {
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                    cameraIntent.addFlags(
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    cameraIntents.add(cameraIntent)
                }

                // Document picker
                val contentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                    if (cameraIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toTypedArray())
                    }
                }

                return try {
                    openFileLauncher.launch(chooser)
                    true
                } catch (_: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback?.onReceiveValue(emptyArray())
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // Load your site (HTTPS)
        webView.loadUrl("https://stockifysoufian.netlify.app/")
    }

    private fun createTempImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
        } catch (_: Exception) {
            null
        }
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
