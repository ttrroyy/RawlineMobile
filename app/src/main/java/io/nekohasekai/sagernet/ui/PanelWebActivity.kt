package app.rawline.mobile.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.R
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.PanelTunnels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A panel that listens only on the VPS's 127.0.0.1, opened through an SSH forward to this phone's 127.0.0.1. The forward
 * lives with this screen; the VPN and the system's routes stay as they are.
 */
class PanelWebActivity : ServerScreenActivity() {
    private var web: WebView? = null
    private var serverId = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = intent.getStringExtra("server").orEmpty()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            title = "WDTT Panel"; setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
        }
        val content = FrameLayout(this)
        content.addView(ProgressBar(this), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(toolbar); root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets -> val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()); view.setPadding(b.left, b.top, b.right, b.bottom); insets }
        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching {
                val server = DeployStore.all().firstOrNull { it.id == serverId } ?: error("Не удалось открыть VPS")
                "http://127.0.0.1:${PanelTunnels.open(server)}${server["panelPath"]}"
            } }
            val url = opened.getOrElse {
                Toast.makeText(this@PanelWebActivity, "Не удалось открыть панель: " + (it.message ?: "SSH недоступен"), Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            web = WebView(this@PanelWebActivity).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    // The panel stays in this screen; other links go to the browser.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (uri.host == "127.0.0.1") return false
                        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
                        return true
                    }
                }
                loadUrl(url)
            }
            content.removeAllViews(); content.addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { web?.takeIf { it.canGoBack() }?.goBack() ?: finish() }
        })
    }

    override fun onDestroy() {
        web?.apply { stopLoading(); (parent as? android.view.ViewGroup)?.removeView(this); destroy() }; web = null
        if (isFinishing) Thread { PanelTunnels.close(serverId) }.start()
        super.onDestroy()
    }
}
