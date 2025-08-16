package com.soufian.stockify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // ---- file chooser state ----
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val openFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileChooserCallback?.onReceiveValue(uris)
            fileChooserCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // ----- WebView configuration -----
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW   // avoid blocked sub-resources
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = object : WebChromeClient() {

            // Camera/mic permissions requested by the web page (getUserMedia for scanner)
            override fun onPermissionRequest(request: PermissionRequest) {
                // If we don't already have Android CAMERA runtime permission, ask first
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestRuntimePermissions {
                        // After user responds, grant whatever the page asked for
                        runOnUiThread { request.grant(request.resources) }
                    }
                } else {
                    runOnUiThread { request.grant(request.resources) }
                }
            }

            // HTML <input type="file"> handler (Excel upload)
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // cancel any previous flow
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                // Respect the site’s requested MIME types when possible
                val p = fileChooserParams
                val intent = (p?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT)).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // If page requested specific types, pass them; else allow anything
                    val types = p?.acceptTypes?.filter { it.isNotBlank() }?.toTypedArray()
                    if (types != null && types.isNotEmpty()) {
                        type = if (types.size == 1) types[0] else "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, types)
                    } else {
                        type = "*/*"
                    }
                    // Excel commonly uses these—helps some pickers:
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                        "text/csv",
                        "*/*"
                    ))
                }

                return try {
                    // ACTION_OPEN_DOCUMENT doesn’t need storage permission and works on Android 13+
                    openFile.launch(intent)
                    true
                } catch (e: Exception) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    false
                }
            }
        }

        // Ask app-level runtime permissions once
        requestRuntimePermissions()

        // Load your site (HTTPS required for camera)
        webView.loadUrl("https://stockifysoufian.netlify.app/")
    }

    private fun requestRuntimePermissions(after: (() -> Unit)? = null) {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.CAMERA

        // Not strictly needed for ACTION_OPEN_DOCUMENT, but harmless for gallery use
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (need.isEmpty()) {
            after?.invoke()
        } else {
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { after?.invoke() }.launch(need.toTypedArray())
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
