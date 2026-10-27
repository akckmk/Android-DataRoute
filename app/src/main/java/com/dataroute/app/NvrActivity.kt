package com.dataroute.app

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.net.Uri

/**
 * Minimal in-app browser for the NVR's local web UI. It goes through the normal network stack, so with the
 * tunnel running the traffic is sent over Wi-Fi no matter which network Android considers "default".
 * UniFi consoles use self-signed certificates; those are accepted for the configured NVR address only.
 */
class NvrActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs(this).load()
        val host = cfg.nvrHost.trim()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    val errHost = Uri.parse(error.url).host
                    if (cfg.acceptNvrCert && host.isNotEmpty() && errHost == host) handler.proceed() else handler.cancel()
                }
            }
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else if (host.isNotEmpty()) web.loadUrl("https://$host")
        else web.loadData("<h3>Set the NVR address in DataRoute settings first.</h3>", "text/html", "utf-8")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
