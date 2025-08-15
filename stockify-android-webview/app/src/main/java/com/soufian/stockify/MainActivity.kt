package com.soufian.stockify

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private lateinit var openDoc: ActivityResultLauncher<Intent>

    private val CAMERA_PERM = Manifest.permission.CAMERA
    private val READ_PERM =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // WebView settings
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
        }

        // Handle getUserMedia (camera/mic) from page
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Grant camera/mic automatically to the WebView origin
                request.grant(request.resources)
            }

            // File chooser for <input type="file">
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback

                // Prepare camera intent
                val imgFile = createImageFile()
                cameraImageUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    imgFile
                )
                val takePicture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Document picker
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/*"))
                }

                // Ask for permissions if needed, then open chooser
                ensurePermissions {
                    val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentIntent)
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePicture))
                        putExtra(Intent.EXTRA_TITLE, "Select file")
                    }
                    openDoc.launch(chooser)
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false // load everything inside the WebView
            }
        }

        // Activity result for chooser
        openDoc = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val resultUris: Array<Uri>? = when {
                res.resultCode != Activity.RESULT_OK -> null
                res.data == null && cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> {
                    val data = res.data!!
                    if (data.clipData != null) {
                        Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                    } else if (data.data != null) {
                        arrayOf(data.data!!)
                    } else null
                }
            }
            fileCallback?.onReceiveValue(resultUris)
            fileCallback = null
            cameraImageUri = null
        }

        // Load your site
        webView.loadUrl("https://stockifysoufian.netlify.app/")
    }

    private fun ensurePermissions(after: () -> Unit) {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERM) != PackageManager.PERMISSION_GRANTED)
            need += CAMERA_PERM
        if (ContextCompat.checkSelfPermission(this, READ_PERM) != PackageManager.PERMISSION_GRANTED)
            need += READ_PERM

        if (need.isEmpty()) {
            after()
        } else {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 1001)
            // We’ll call 'after' again from onRequestPermissionsResult if granted
            pendingAfter = after
        }
    }

    private var pendingAfter: (() -> Unit)? = null
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingAfter?.invoke()
        } else {
            // If denied, return null to file chooser so it closes gracefully
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
        pendingAfter = null
    }

    private fun createImageFile(): File {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_$time.jpg"
        val dir = getExternalFilesDir("Pictures")!!
        return File(dir, fileName)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}                    val c = data.clipData!!
                    Array(c.itemCount) { i -> c.getItemAt(i).uri }
                }
                else -> null
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
            cameraPhotoUri = null
        }

    // Launcher for runtime permissions
    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = object : WebChromeClient() {

            // Allow getUserMedia (camera/mic) from HTTPS page
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    request.grant(request.resources)
                }
            }

            // Handle <input type="file"> (gallery/camera)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val accept = fileChooserParams.acceptTypes.joinToString(",").lowercase()
                val wantsImages = accept.contains("image")
                val captureEnabled = fileChooserParams.isCaptureEnabled

                // Intent to choose file(s)
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (wantsImages) "image/*" else "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                // Optional camera intent
                val chooserIntents = mutableListOf<Intent>()
                if (captureEnabled && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                    val photoFile = File.createTempFile("camera_", ".jpg", cacheDir)
                    cameraPhotoUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        photoFile
                    )
                    val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    chooserIntents.add(takePictureIntent)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    if (chooserIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, chooserIntents.toTypedArray())
                    }
                }

                openFileLauncher.launch(chooser)
                return true
            }
        }

        // Ask runtime camera + media permission (varies by Android version)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPerms.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_IMAGES
            ))
        } else {
            requestPerms.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ))
        }

        // Load your site (must be HTTPS for getUserMedia)
        webView.loadUrl("https://stockifysoufian.netlify.app/")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
=======
}
>>>>>>> 101820ef56b484c1e33266901a6692e53a4add18
