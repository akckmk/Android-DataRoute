package com.dataroute.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dataroute.core.Ip
import com.dataroute.core.LocalDns
import com.dataroute.core.RoutePolicy

/**
 * In-app browser for home services' web pages (UniFi Protect, Home Assistant, Plex, Jellyfin, a NAS…).
 * It uses the normal network stack, so with routing on it reaches them over Wi-Fi whatever Android's default is.
 * Home devices usually have self-signed certificates; those are accepted for local addresses only.
 */
class WebViewerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun intent(ctx: Context, url: String, title: String): Intent =
            Intent(ctx, WebViewerActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)

        /** Only certificates of devices on the home network may be accepted without a trusted CA. */
        fun isLocalHost(host: String?, cfg: Config): Boolean {
            val h = host?.lowercase()?.trimEnd('.') ?: return false
            Ip.parse(h)?.let { ip -> return RoutePolicy.PRIVATE_RANGES.any { it.contains(ip) } || h == cfg.nvrHost.trim() }
            if (cfg.services.any { it.host.equals(h, ignoreCase = true) }) return true
            if (!h.contains('.') || h.endsWith(".local")) return true
            val suffixes = LocalDns.DEFAULT_SUFFIXES + LocalDns.parseSuffixes(cfg.localDomains)
            return suffixes.any { h == it || h.endsWith(".$it") } || LocalDns.parseHosts(cfg.localHosts).first.containsKey(h)
        }
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cfg = Prefs(this).load()
        val url = intent.getStringExtra(EXTRA_URL) ?: cfg.nvrHost.trim().takeIf { it.isNotEmpty() }?.let { "https://$it" }
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    if (cfg.acceptNvrCert && isLocalHost(Uri.parse(error.url).host, cfg)) handler.proceed() else handler.cancel()
                }
            }
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.window_bg))
            addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(frame)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        when {
            savedInstanceState != null -> web.restoreState(savedInstanceState)
            url != null -> web.loadUrl(url)
            else -> web.loadData("<h3 style='font-family:sans-serif'>Add the address in DataRoute first.</h3>", "text/html", "utf-8")
        }
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
