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
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://stockifysoufian.netlify.app/"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // file upload (incl. camera) state
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    // activity result launcher for file chooser
    private val pickContent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res: ActivityResult ->
            val callback = filePathCallback
            filePathCallback = null

            if (callback == null) return@registerForActivityResult

            if (res.resultCode != RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val data: Intent? = res.data

            // 1) A photo we captured with camera?
            if (data == null && cameraPhotoUri != null) {
                callback.onReceiveValue(arrayOf(cameraPhotoUri!!))
                cameraPhotoUri = null
                return@registerForActivityResult
            }

            // 2) One or many files from picker
            val results = mutableListOf<Uri>()
            if (data?.clipData != null) {
                val clip: ClipData = data.clipData!!
                for (i in 0 until clip.itemCount) {
                    results.add(clip.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                results.add(data.data!!)
            }

            callback.onReceiveValue(if (results.isEmpty()) null else results.toTypedArray())
        }

    // request CAMERA for native capture (not for JS getUserMedia — that’s via onPermissionRequest)
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // If user grants during an active chooser, re-open it:
            if (granted && filePathCallback != null) openChooser(acceptImagesOnly = true, isCapture = true)
            else if (!granted) filePathCallback?.onReceiveValue(null)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        // WebView setup
        WebView.setWebContentsDebuggingEnabled(true)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + " StockifyAndroidWebView"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // Allow in-page barcode scanner/mic via getUserMedia
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    request.grant(request.resources) // grant camera/mic requested by the page
                }
            }

            // <input type="file"> handler
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback

                val accepts = fileChooserParams.acceptTypes?.joinToString(",")?.lowercase(Locale.US) ?: ""
                val capture = fileChooserParams.isCaptureEnabled
                val wantsImagesOnly = accepts.contains("image")

                if (capture && wantsImagesOnly) {
                    // need camera permission for ACTION_IMAGE_CAPTURE
                    if (ContextCompat.checkSelfPermission(this@MainActivity, CAMERA_PERMISSION)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestCameraPermission.launch(CAMERA_PERMISSION)
                        return true
                    }
                }

                openChooser(acceptImagesOnly = wantsImagesOnly, isCapture = capture && wantsImagesOnly)
                return true
            }
        }

        // Pull-to-refresh
        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.isRefreshing = true
        webView.loadUrl(HOME_URL)
    }

    private fun openChooser(acceptImagesOnly: Boolean, isCapture: Boolean) {
        val intents = mutableListOf<Intent>()

        // Camera capture
        if (isCapture) {
            try {
                val photoFile = createTempImageFile()
                val authority = "${packageName}.fileprovider" // <-- fix: no BuildConfig
                cameraPhotoUri = FileProvider.getUriForFile(this, authority, photoFile)

                val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                }
                intents.add(captureIntent)
            } catch (_: Exception) {
                // fall back to picker only
                cameraPhotoUri = null
            }
        }

        // Normal picker
        val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (acceptImagesOnly) "image/*" else "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, pickIntent)
            if (intents.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
        }

        try {
            pickContent.launch(chooser)
        } catch (_: ActivityNotFoundException) {
            // Nothing can handle – cleanly fail
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private fun createTempImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_${timeStamp}_"
        val storageDir = cacheDir // no external storage permission needed
        return File.createTempFile(fileName, ".jpg", storageDir)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // Keep WebView state across config changes (optional; prevents reloads)
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }
}
