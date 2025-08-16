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

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val pickContent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res: ActivityResult ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult

            if (res.resultCode != RESULT_OK) {
                callback.onReceiveValue(null); return@registerForActivityResult
            }

            val data: Intent? = res.data
            if (data == null && cameraPhotoUri != null) {
                callback.onReceiveValue(arrayOf(cameraPhotoUri!!))
                cameraPhotoUri = null
                return@registerForActivityResult
            }

            val results = mutableListOf<Uri>()
            if (data?.clipData != null) {
                val clip: ClipData = data.clipData!!
                for (i in 0 until clip.itemCount) results.add(clip.getItemAt(i).uri)
            } else if (data?.data != null) results.add(data.data!!)
            callback.onReceiveValue(if (results.isEmpty()) null else results.toTypedArray())
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && filePathCallback != null) {
                openChooser(acceptImagesOnly = true, isCapture = true)
            } else if (!granted) filePathCallback?.onReceiveValue(null)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        // WebView config
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
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) } // allow getUserMedia
            }
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                val accepts = fileChooserParams.acceptTypes?.joinToString(",")
                    ?.lowercase(Locale.US) ?: ""
                val capture = fileChooserParams.isCaptureEnabled
                val wantsImagesOnly = accepts.contains("image")
                if (capture && wantsImagesOnly) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity, CAMERA_PERMISSION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        requestCameraPermission.launch(CAMERA_PERMISSION); return true
                    }
                }
                openChooser(acceptImagesOnly = wantsImagesOnly, isCapture = capture && wantsImagesOnly)
                return true
            }
        }

        // --- Pull-to-refresh: only when WebView is at top ---
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            // return true = child can scroll up (so DON'T refresh)
            webView.scrollY > 0 || webView.canScrollVertically(-1)
        }
        if (Build.VERSION.SDK_INT >= 23) {
            webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                swipeRefresh.isEnabled = scrollY == 0
            }
        }
        // ----------------------------------------------------

        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.isRefreshing = true
        webView.loadUrl(HOME_URL)
    }

    private fun openChooser(acceptImagesOnly: Boolean, isCapture: Boolean) {
        val initialIntents = mutableListOf<Intent>()
        if (isCapture) {
            try {
                val photoFile = createTempImageFile()
                val authority = "${packageName}.fileprovider"
                cameraPhotoUri = FileProvider.getUriForFile(this, authority, photoFile)
                val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                }
                initialIntents.add(capture)
            } catch (_: Exception) {
                cameraPhotoUri = null
            }
        }
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (acceptImagesOnly) "image/*" else "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, pick)
            if (initialIntents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
            }
        }
        try {
            pickContent.launch(chooser)
        } catch (_: ActivityNotFoundException) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private fun createTempImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = cacheDir
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::webView.isInitialized) webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (this::webView.isInitialized) webView.restoreState(savedInstanceState)
    }
}
