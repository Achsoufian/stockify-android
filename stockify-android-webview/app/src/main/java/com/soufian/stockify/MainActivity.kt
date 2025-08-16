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
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


private const val START_URL = "https://stockifysoufian.netlify.app/"

class MainActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webView: WebView

    // ---- File chooser / camera ----
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private val FILE_CHOOSER_REQ = 1001

    // ---- Permissions ----
    private val RUNTIME_PERMS_REQ = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        setupWebView()
        ensureRuntimePermissions()

        // Pull-to-refresh
        swipeRefresh.setOnRefreshListener { webView.reload() }

        // Finish refresh spinner after page load
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Let WebView load http/https; block others so intents like tel:, mailto: stay inside app or are handled gracefully
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    // Try to hand off to system if it isn't http(s)
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "Can't open link", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
                super.onPageFinished(view, url)
            }
        }

        // Back button goes back in WebView history
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // First load
        if (savedInstanceState == null) webView.loadUrl(START_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.loadsImagesAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = s.userAgentString + " StockifyWebView"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {

            // Camera/mic permission requested by WebRTC / getUserMedia in page
            override fun onPermissionRequest(request: PermissionRequest?) {
                // Grant camera + mic
                request?.grant(request.resources)
            }

            // File chooser for <input type="file"> with camera option
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                // Camera intent (photo)
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                val photoFile = try {
                    createImageFile()
                } catch (e: Exception) {
                    null
                }
                if (photoFile != null) {
                    cameraPhotoUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                } else {
                    cameraIntent.putExtra("return-data", true)
                }

                // Content chooser (any type, filtered by acceptTypes if present)
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    // Use the site’s accept types if provided
                    fileChooserParams?.acceptTypes?.let { accepts ->
                        if (accepts.isNotEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, accepts)
                        }
                    }
                    // Allow multiple if requested
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }

                // Create chooser with camera + picker
                val intentArray = arrayListOf<Intent>()
                if (cameraIntent.resolveActivity(packageManager) != null) {
                    intentArray.add(cameraIntent)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray.toTypedArray())
                }

                return try {
                    startActivityForResult(chooser, FILE_CHOOSER_REQ)
                    true
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No file chooser found", Toast.LENGTH_SHORT).show()
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }
    }

    // Create a temp image for camera capture
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    // Handle file chooser result
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQ) {
            val callback = filePathCallback
            val results: Array<Uri>? = when {
                resultCode != Activity.RESULT_OK -> null
                data == null || data.data == null -> {
                    // Camera case
                    cameraPhotoUri?.let { arrayOf(it) }
                }
                else -> {
                    // Picked from storage (single or multiple)
                    if (data.clipData != null) {
                        Array(data.clipData!!.itemCount) { i ->
                            data.clipData!!.getItemAt(i).uri
                        }
                    } else {
                        arrayOf(data.data!!)
                    }
                }
            }
            callback?.onReceiveValue(results)
            filePathCallback = null
            cameraPhotoUri = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // Ask for runtime permissions the first time
    private fun ensureRuntimePermissions() {
        val needed = mutableListOf<String>()

        // Camera for barcode scanning / file capture
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.CAMERA
        }

        // Storage (gallery). Android 13+ uses READ_MEDIA_*; older uses READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.READ_MEDIA_IMAGES
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), RUNTIME_PERMS_REQ)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RUNTIME_PERMS_REQ) {
            // If denied, we still let WebView try; page will show its own message.
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Toast.makeText(this, "Some permissions were denied. Features may be limited.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
