package com.soufian.stockify

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private val HOME_URL = "https://stockifysoufian.netlify.app/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        // --- WebView settings
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT          // <-- let WebView manage cache
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadsImagesAutomatically = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // --- Pull to refresh
        swipeRefresh.setOnRefreshListener { webView.reload() }

        webView.webViewClient = object : WebViewClient() {

            // Let WebView handle http(s) itself. Only intercept non-http(s) schemes.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) { /* ignore */ }
                    true
                }
            }

            // Stop the spinner when a page has finished loading
            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                super.onPageFinished(view, url)
            }

            // Handle main-frame load errors (incl. net::ERR_CACHE_MISS mapped as UNKNOWN)
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showRetryFallback(view)
                }
            }

            // Old API fallback (covers some devices)
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                showRetryFallback(view)
            }

            private fun showRetryFallback(view: WebView) {
                swipeRefresh.isRefreshing = false
                val html = """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1"/>
                    <style>body{font-family:sans-serif;margin:32px}button{padding:12px 18px;font-size:16px}</style>
                    </head><body>
                    <h3>Couldn’t load the page</h3>
                    <p>Please check your connection and try again.</p>
                    <button onclick="location.replace('$HOME_URL')">Retry</button>
                    </body></html>
                """.trimIndent()
                view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Downloads support
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading file…")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                allowScanningByMediaScanner()
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
                )
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        }

        // First load
        swipeRefresh.isRefreshing = true
        webView.loadUrl(HOME_URL)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
