package com.soufian.stockify

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // your site
        private const val HOME_URL = "https://stockifysoufian.netlify.app/"
        private const val FILE_PROVIDER_AUTHORITY = "com.soufian.stockify.fileprovider"
    }

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout

    // <input type=file> callback
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // photo capture
    private var cameraPhotoUri: Uri? = null

    /** -------- Activity Result launchers -------- */

    // File chooser result
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val callback = fileChooserCallback
            fileChooserCallback = null

            if (callback == null) return@registerForActivityResult

            var results: Array<Uri>? = null

            if (result.resultCode == RESULT_OK) {
                val data = result.data

                // If camera is used (no data, but we have a captured photo)
                if (data == null || data.data == null) {
                    cameraPhotoUri?.let {
                        results = arrayOf(it)
                    }
                } else {
                    // from file picker
                    data.data?.let {
                        results = arrayOf(it)
                    }
                    // multiple
                    data.clipData?.let { clip: ClipData ->
                        val list = mutableListOf<Uri>()
                        for (i in 0 until clip.itemCount) {
                            list.add(clip.getItemAt(i).uri)
                        }
                        results = list.toTypedArray()
                    }
                }
            }
            callback.onReceiveValue(results ?: emptyArray())
        }

    // Optional: runtime permission request (camera)
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    /** ------------------------------------------- */

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipe = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        // WebView basic setup
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Let http/https load in WebView
                if (url.startsWith("http://") || url.startsWith("https://")) return false

                // Handle tel:, mailto:, etc.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                } catch (_: Exception) {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                swipe.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipe.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // FILE INPUT HANDLER (camera + file picker)
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.fileChooserCallback = filePathCallback

                val allowMultiple =
                    fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                // ***** Camera intent *****
                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).let { intent ->
                    try {
                        val photoFile = createImageFile()
                        cameraPhotoUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            FILE_PROVIDER_AUTHORITY,
                            photoFile
                        )
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent
                    } catch (ex: IOException) {
                        null
                    }
                }

                // ***** Very permissive file picker *****
                // Some file managers on MIUI/older devices fail if we pass a strict MIME.
                // We keep it generic so the system always shows at least the "Files" app.
                val mimeTypes = arrayOf("*/*")

                val openDoc = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Build chooser with both picker and camera if available
                val initialIntents = mutableListOf<Intent>()
                takePictureIntent?.let { initialIntents.add(it) }
                initialIntents.add(getContent)

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, openDoc)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
                }

                return try {
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (_: ActivityNotFoundException) {
                    // Last-ditch fallback
                    return try {
                        fileChooserLauncher.launch(getContent)
                        true
                    } catch (_: Exception) {
                        this@MainActivity.fileChooserCallback?.onReceiveValue(emptyArray())
                        this@MainActivity.fileChooserCallback = null
                        false
                    }
                }
            }
        }

        // Pull-to-refresh: reload current page
        swipe.setOnRefreshListener {
            webView.reload()
        }

        // First load
        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }

        // Ask camera permission once (optional – only when first needed)
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    /** Create a temp file for camera capture */
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
