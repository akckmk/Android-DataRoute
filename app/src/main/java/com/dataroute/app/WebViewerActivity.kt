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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslCertificate
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dataroute.core.Ip
import com.dataroute.core.LocalDns
import com.dataroute.core.RoutePolicy
import com.dataroute.core.TrustOnFirstUse

/**
 * In-app browser for home services' web pages (UniFi Protect, Home Assistant, Plex, Jellyfin, a NAS…).
 * It uses the normal network stack, so with routing on it reaches them over Wi-Fi whatever Android's default is.
 * Home devices usually have self-signed certificates; those are accepted for local addresses only.
 */
class WebViewerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val PINS = "cert_pins"

        /** Remembered home-device certificates (host:port → SHA-256). */
        fun pins(ctx: Context) = ctx.applicationContext.getSharedPreferences(PINS, Context.MODE_PRIVATE)

        fun forgetPins(ctx: Context) = pins(ctx).edit().clear().apply()

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
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    val uri = Uri.parse(error.url)
                    val host = uri.host
                    if (!cfg.acceptNvrCert || host == null || !isLocalHost(host, cfg)) { handler.cancel(); return }
                    // Trust on first use: remember this device's certificate, refuse a different one later.
                    val der = certificateDer(error.certificate)
                    if (der == null) { handler.cancel(); return }
                    val port = if (uri.port > 0) uri.port else 443
                    val prefs = pins(this@WebViewerActivity)
                    val tofu = TrustOnFirstUse({ prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).apply() })
                    when (tofu.check(host, port, der)) {
                        TrustOnFirstUse.Verdict.FIRST_USE_TRUSTED, TrustOnFirstUse.Verdict.MATCHES -> handler.proceed()
                        TrustOnFirstUse.Verdict.CHANGED -> {
                            handler.cancel()
                            view.loadDataWithBaseURL(
                                null, changedCertificatePage(host), "text/html", "utf-8", null,
                            )
                        }
                    }
                }

                // Web pages only: never let a page launch other apps or open local files (intent:, file:, content:…).
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "http" && scheme != "https"
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

    private fun certificateDer(cert: SslCertificate): ByteArray? =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            cert.x509Certificate?.encoded
        } else {
            SslCertificate.saveState(cert)?.getByteArray("x509-certificate")
        }

    private fun changedCertificatePage(host: String): String {
        val safeHost = android.text.TextUtils.htmlEncode(host)
        return """
            <html><body style="font-family:sans-serif;padding:24px;line-height:1.5;color:#23213A;background:#F6F4FF">
            <h2 style="color:#C0262C">Blocked: this device's certificate changed</h2>
            <p><b>$safeHost</b> presented a different certificate from the one DataRoute remembered.</p>
            <p>If you're not on your home network, something may be pretending to be your device. Don't sign in.</p>
            <p>If you replaced or reset the device at home, go to DataRoute <b>Settings</b> and tap
            <b>Forget trusted certificates</b>, then open it again.</p></body></html>
        """.trimIndent()
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
