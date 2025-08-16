package com.soufian.stockify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
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

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // ====== File upload state ======
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    // Replace with your site
    private val HOME_URL = "https://stockifysoufian.netlify.app/"

    // Ask for runtime permissions (camera + storage) if needed
    private val runtimePerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    // Result for chooser (files and/or camera photo)
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res: ActivityResult ->
            handleFileChooserResult(res)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        setupSwipeRefresh()
        setupWebView()
        requestRuntimePermsIfNeeded()

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        }

        // Back navigation inside WebView
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_blue_light,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }

        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Let WebView handle normal http/https
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                swipeRefresh.isRefreshing = true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // ✅ IMPORTANT: allow in-page camera (web scanner) access
            override fun onPermissionRequest(request: PermissionRequest) {
                // Grant camera/mic when the page asks (getUserMedia)
                val resources = request.resources
                val allowed = resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }.toTypedArray()

                if (allowed.isNotEmpty()) {
                    request.grant(allowed)
                } else {
                    request.deny()
                }
            }

            // ✅ File upload + camera capture
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null) // clean any previous
                fileCallback = filePathCallback

                // Document picker for Excel/CSV/images
                val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES, arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "text/csv",
                            "image/*"
                        )
                    )
                }

                // Optional camera capture (image/* input with capture)
                val cameraIntent = makeCameraIntent()

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, docIntent)
                    cameraIntent?.let {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it))
                    }
                }

                return try {
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No file picker available", Toast.LENGTH_SHORT).show()
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }
    }

    private fun requestRuntimePermsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.CAMERA

        // Storage isn’t strictly required for SAF, but camera save needs it on older APIs
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) runtimePerms.launch(needed.toTypedArray())
    }

    // ===== Helpers =====

    private fun makeCameraIntent(): Intent? {
        return try {
            val photoFile = createTempImageFile()
            val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
            cameraPhotoUri = FileProvider.getUriForFile(this, authority, photoFile)

            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createTempImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun handleFileChooserResult(res: ActivityResult) {
        val callback = fileCallback ?: return
        var results: Array<Uri>? = null

        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data

            // If user took a photo with camera
            if (data == null && cameraPhotoUri != null) {
                results = arrayOf(cameraPhotoUri!!)
            } else if (data != null) {
                val clip = data.clipData
                if (clip != null && clip.itemCount > 0) {
                    results = Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                } else if (data.data != null) {
                    results = arrayOf(data.data!!)
                }
            }
        }

        callback.onReceiveValue(results)
        fileCallback = null
        cameraPhotoUri = null
        swipeRefresh.isRefreshing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
    }
}
