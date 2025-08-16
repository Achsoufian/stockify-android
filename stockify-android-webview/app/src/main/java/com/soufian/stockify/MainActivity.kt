package com.soufian.stockify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val resultUri: Uri? = data?.data
                if (resultUri != null) {
                    filePathCallback?.onReceiveValue(arrayOf(resultUri))
                } else {
                    filePathCallback?.onReceiveValue(null)
                }
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        webView = findViewById(R.id.webView)

        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webSettings.safeBrowsingEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefreshLayout.isRefreshing = false
                swipeRefreshLayout.isEnabled = (webView.scrollY == 0)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                fileChooserLauncher.launch(intent)
                return true
            }
        }

        webView.loadUrl("https://your-website-url.com")

        // Pull to refresh: only when at the very top
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }
        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            webView.canScrollVertically(-1)
        }

        // Make the pull distance a bit longer so it doesn’t refresh accidentally
        val trigger = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 120f, resources.displayMetrics
        ).toInt()
        swipeRefreshLayout.setDistanceToTriggerSync(trigger)

        // Update refresh availability when scrolling
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefreshLayout.isEnabled = (scrollY == 0)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
