package com.soufian.stockify

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // For <input type="file">
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val dataUris: Array<Uri> = when {
                result.resultCode != RESULT_OK -> emptyArray()
                result.data == null && cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
                else -> {
                    val data = result.data!!
                    val clip: ClipData? = data.clipData
                    when {
                        clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                        data.data != null -> arrayOf(data.data!!)
                        else -> emptyArray()
                    }
                }
            }
            filePathCallback?.onReceiveValue(dataUris)
            filePathCallback = null
            cameraPhotoUri = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        // Basic WebView setup
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + " StockifyAndroidWebView"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // Handle <input type="file"> chooser + camera
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback

                // Build camera intent (optional)
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                val photoFile = createTempImageFile()
                val cameraIntents = mutableListOf<Intent>()
                cameraPhotoUri = photoFile?.let {
                    FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        it
                    )
                }
                if (cameraPhotoUri != null) {
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                    cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    cameraIntents.add(cameraIntent)
                }

                // Document picker
                val contentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                // Create chooser including camera
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

        // Ask for camera permission lazily if needed
        maybeRequestCamera()

        // Load your site
        webView.loadUrl("https://stockifysoufian.netlify.app/")
    }

    private fun maybeRequestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            }
        }
    }

    private fun createTempImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "IMG_${timeStamp}"
            val storageDir = cacheDir
            File.createTempFile(fileName, ".jpg", storageDir)
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
