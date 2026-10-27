package com.eclipse.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.eclipse.ai.service.GatewayService
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ECLIPSE_URL = "http://127.0.0.1:5000/"
        private const val JANITOR_URL = "https://janitorai.com/"
    }

    private lateinit var webViewEclipse: WebView
    private lateinit var webViewJanitor: WebView
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var rootLayout: View

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?: when {
                result.resultCode == Activity.RESULT_OK && result.data?.data != null -> arrayOf(result.data!!.data!!)
                result.resultCode == Activity.RESULT_OK && result.data?.clipData != null -> {
                    val clipData = result.data!!.clipData!!
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
                else -> null
            }
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        webViewEclipse = findViewById(R.id.webViewEclipse)
        webViewJanitor = findViewById(R.id.webViewJanitor)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvStatus = findViewById(R.id.tvStatus)
        bottomNav = findViewById(R.id.bottomNav)

        requestRequiredPermissions()
        setupKeyboardListener()
        setupWebViews()
        setupBottomNav()
        setupBackNavigation()

        startAndPollGateway()
    }

    private fun requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun setupKeyboardListener() {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            bottomNav.visibility = if (imeVisible) View.GONE else View.VISIBLE
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViews() {
        configureWebView(webViewEclipse)
        configureWebView(webViewJanitor)

        webViewEclipse.webViewClient = createWebViewClient()
        webViewJanitor.webViewClient = createWebViewClient()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT

            val currentUa = userAgentString
            userAgentString = currentUa.replace("; wv", "")
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch file chooser: ${e.message}")
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    return false
                }
                return true
            }
        }
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val scheme = request.url?.scheme?.lowercase() ?: ""

                // OAuth providers that block WebViews or require system browser for security
                val isOAuthRedirect = url.contains("accounts.google.com") ||
                        url.contains("github.com/login/oauth") ||
                        url.contains("discord.com/oauth2") ||
                        url.contains("discord.com/api/oauth2") ||
                        url.contains("appleid.apple.com/auth") ||
                        url.contains("twitter.com/i/oauth2")

                val isExternalScheme = scheme != "http" && scheme != "https" &&
                        scheme != "about" && scheme != "data" && scheme != "javascript"

                if (isOAuthRedirect || isExternalScheme) {
                    try {
                        val intent = if (url.startsWith("intent:")) {
                            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        }
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot launch external URL intent: ${e.message}")
                    }
                }
                return false
            }
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_eclipse -> {
                    switchTab(R.id.nav_eclipse)
                    true
                }
                R.id.nav_janitor -> {
                    switchTab(R.id.nav_janitor)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchTab(tabId: Int) {
        when (tabId) {
            R.id.nav_eclipse -> {
                webViewEclipse.visibility = View.VISIBLE
                webViewJanitor.visibility = View.GONE
            }
            R.id.nav_janitor -> {
                webViewEclipse.visibility = View.GONE
                webViewJanitor.visibility = View.VISIBLE
                if (webViewJanitor.url == null) {
                    webViewJanitor.loadUrl(JANITOR_URL)
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val activeWebView = if (webViewEclipse.visibility == View.VISIBLE) webViewEclipse else webViewJanitor
                if (activeWebView.canGoBack()) {
                    activeWebView.goBack()
                } else if (webViewJanitor.visibility == View.VISIBLE) {
                    bottomNav.selectedItemId = R.id.nav_eclipse
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun startAndPollGateway() {
        GatewayService.startService(this)
        tvStatus.text = getString(R.string.starting_gateway)

        lifecycleScope.launch {
            var online = false
            for (attempt in 1..40) {
                if (isGatewayPortReady()) {
                    online = true
                    break
                }
                delay(500)
            }

            loadingOverlay.visibility = View.GONE
            if (online) {
                webViewEclipse.loadUrl(ECLIPSE_URL)
            } else {
                tvStatus.text = "Gateway taking longer than expected, loading..."
                webViewEclipse.loadUrl(ECLIPSE_URL)
            }
        }
    }

    private suspend fun isGatewayPortReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 5000), 200)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        webViewEclipse.destroy()
        webViewJanitor.destroy()
        super.onDestroy()
    }
}
