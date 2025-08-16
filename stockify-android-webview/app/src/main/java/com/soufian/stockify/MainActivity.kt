package com.soufian.stockify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val START_URL = "https://stockifysoufian.netlify.app/"

class MainActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webView: WebView

    // File chooser / camera
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private val FILE_CHOOSER_REQ = 1001
    private val RUNTIME_PERMS_REQ = 2001

    private var isWebReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // ✅ Install splash first; keep it until web loads
        val splash: SplashScreen = installSplashScreen()
        splash.setKeepOnScreenCondition { !isWebReady }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Pull to refresh
        swipeRefresh.setOnRefreshListener { webView.reload() }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "Can't open link", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                isWebReady = true           // ✅ hide splash
                swipeRefresh.isRefreshing = false
                super.onPageFinished(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Grant getUserMedia (camera/mic) for scanner
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), RUNTIME_PERMS_REQ)
                }
                runOnUiThread { request.grant(request.resources) }
            }

            // File chooser with camera option
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                val photoFile = try { createImageFile() } catch (_: Exception) { null }
                if (photoFile != null) {
                    cameraPhotoUri = FileProvider.getUriForFile(
                        this@MainActivity, "${packageName}.fileprovider", photoFile
                    )
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                } else {
                    cameraIntent.putExtra("return-data", true)
                }

                val contentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                        "text/csv",
                        "*/*"
                    ))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                }

                val intents = arrayListOf(cameraIntent)
                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
                }

                return try {
                    startActivityForResult(chooser, FILE_CHOOSER_REQ)
                    true
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No file picker found", Toast.LENGTH_SHORT).show()
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // Back navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // Permissions once
        ensureRuntimePermissions()

        if (savedInstanceState == null) webView.loadUrl(START_URL)
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", dir)
    }

    @Deprecated("onActivityResult is fine for this simple chooser")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQ) {
            val cb = filePathCallback
            val res: Array<Uri>? = when {
                resultCode != Activity.RESULT_OK -> null
                data == null || data.data == null -> cameraPhotoUri?.let { arrayOf(it) }
                data.clipData != null -> Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                else -> arrayOf(data.data!!)
            }
            cb?.onReceiveValue(res)
            filePathCallback = null
            cameraPhotoUri = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun ensureRuntimePermissions() {
        val ask = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) ask += Manifest.permission.CAMERA

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) ask += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) ask += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), RUNTIME_PERMS_REQ)
    }
}
