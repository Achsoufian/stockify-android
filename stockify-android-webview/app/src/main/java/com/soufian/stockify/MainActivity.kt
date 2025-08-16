package com.soufian.stockify

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

private const val HOME_URL = "https://stockifysoufian.netlify.app/"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // Permission launcher (CAMERA + read images for Android 13+)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // We don't block if denied; user can still pick from files.
            launchChooser()
        }

    // Chooser result
    private val chooserResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            onChooserResult(result)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipe = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        CookieManager.getInstance().setAcceptCookie(true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + " StockifyWebView"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                swipe.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Android 5.0+ file chooser
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                // Ask permissions just before opening chooser (lightweight UX)
                val perms = mutableListOf(android.Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) {
                    perms += android.Manifest.permission.READ_MEDIA_IMAGES
                }
                permissionLauncher.launch(perms.toTypedArray())
                return true
            }
        }

        // Pull-to-refresh
        swipe.setOnRefreshListener { webView.reload() }

        // Simple downloads handoff to external apps
        webView.setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(url)
                    if (!mimeType.isNullOrEmpty()) {
                        setDataAndType(Uri.parse(url), mimeType)
                    }
                }
                startActivity(intent)
            } catch (_: ActivityNotFoundException) { /* ignore */ }
        })

        // First load
        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /** Build and launch the chooser with Camera + Files */
    private fun launchChooser() {
        // 1) Camera intent (optional if camera exists)
        val cameraIntents = ArrayList<Intent>()
        try {
            val imageFile = createImageFile() ?: throw IOException("temp")
            cameraImageUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                imageFile
            )
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            cameraIntents.add(cameraIntent)
        } catch (_: Exception) {
            cameraImageUri = null
        }

        // 2) File picker (images, excel, pdf…)
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "image/*",
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel"
                )
            )
        }

        // 3) Chooser
        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
            putExtra(Intent.EXTRA_TITLE, "Select file")
            if (cameraIntents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toTypedArray())
            }
        }

        try {
            chooserResultLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            // Fallback: just open files
            chooserResultLauncher.launch(contentIntent)
        }
    }

    /** Receive result and pass back to WebView */
    private fun onChooserResult(result: ActivityResult) {
        val callback = filePathCallback ?: return
        var uris: Array<Uri>? = null

        if (result.resultCode == RESULT_OK) {
            val intent = result.data
            if (intent == null && cameraImageUri != null) {
                // Camera path
                uris = arrayOf(cameraImageUri!!)
            } else if (intent != null) {
                // Single or multiple selection
                intent.clipData?.let { clip ->
                    val list = (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                    uris = list.toTypedArray()
                } ?: run {
                    intent.data?.let { uris = arrayOf(it) }
                }
            }
        }
        callback.onReceiveValue(uris ?: emptyArray())
        filePathCallback = null
        cameraImageUri = null
    }

    /** Create a temp image file in cache for the camera */
    @Throws(IOException::class)
    private fun createImageFile(): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "camera_$timeStamp"
        val storageDir = externalCacheDir ?: cacheDir
        return File.createTempFile(fileName, ".jpg", storageDir)
    }
}
