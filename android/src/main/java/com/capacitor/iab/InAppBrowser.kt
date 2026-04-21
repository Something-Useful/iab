package com.capacitor.iab

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

interface InAppBrowserDelegate {
    fun onLoadStart(url: String)
    fun onLoadStop(url: String)
    fun onLoadError(url: String, code: Int, message: String)
    fun onCloseRequested()
    fun onMessage(data: String)
}

data class InAppBrowserOptions(
    val url: String,
    val headers: Map<String, String>? = null,
    val toolbarColor: Int? = null,
    val title: String? = null,
    val closeButtonText: String = "Done",
    val showNavigationButtons: Boolean = true,
    val showUrlBar: Boolean = true
)

class InAppBrowserDialog(
    private val hostActivity: AppCompatActivity,
    private val options: InAppBrowserOptions,
    private val delegate: InAppBrowserDelegate?
) : Dialog(hostActivity, android.R.style.Theme_Light_NoTitleBar) {

    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var urlLabel: TextView
    private lateinit var closeButton: TextView
    private var backButton: TextView? = null
    private var forwardButton: TextView? = null
    private lateinit var progressBar: ProgressBar
    lateinit var webView: WebView
        private set

    private val pendingScripts = mutableMapOf<String, (Result<String>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val documentEndScripts = mutableListOf<String>()
    private val documentStartHandlers = mutableListOf<ScriptHandler>()

    companion object {
        const val BRIDGE_NAME = "IabBridge"
        // Shim so page JS can use iOS-style window.webkit.messageHandlers.iab.postMessage(data)
        private val POST_MESSAGE_SHIM = """
            (function() {
                if (!window.webkit) window.webkit = {};
                if (!window.webkit.messageHandlers) window.webkit.messageHandlers = {};
                window.webkit.messageHandlers.iab = {
                    postMessage: function(d) {
                        var s = typeof d === 'string' ? d : JSON.stringify(d);
                        $BRIDGE_NAME.postMessage(s);
                    }
                };
            })();
        """
        private val EXTERNAL_SCHEMES = setOf("tel", "mailto", "sms", "geo", "market", "intent")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        buildUI()
        setContentView(rootLayout)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setupWebView()
        loadInitialUrl()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        delegate?.onCloseRequested()
    }

    private fun buildUI() {
        val ctx = context

        rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }

        buildToolbar(ctx)
        rootLayout.addView(toolbar)

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2)
            )
            max = 100
            visibility = View.GONE
        }
        rootLayout.addView(progressBar)

        webView = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootLayout.addView(webView)
    }

    private fun buildToolbar(ctx: android.content.Context) {
        val toolbarHeight = if (options.showUrlBar) dp(64) else dp(48)
        val toolbarBg = options.toolbarColor ?: Color.WHITE

        toolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                toolbarHeight
            )
            setBackgroundColor(toolbarBg)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }

        closeButton = TextView(ctx).apply {
            text = options.closeButtonText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { delegate?.onCloseRequested() }
        }
        toolbar.addView(closeButton)

        val titleContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            gravity = Gravity.CENTER
        }

        titleLabel = TextView(ctx).apply {
            text = options.title ?: ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleContainer.addView(titleLabel)

        urlLabel = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            visibility = if (options.showUrlBar) View.VISIBLE else View.GONE
        }
        titleContainer.addView(urlLabel)

        toolbar.addView(titleContainer)

        if (options.showNavigationButtons) {
            backButton = TextView(ctx).apply {
                text = "‹"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Color.GRAY)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isEnabled = false
                setOnClickListener { webView.goBack() }
            }
            toolbar.addView(backButton)

            forwardButton = TextView(ctx).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Color.GRAY)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isEnabled = false
                setOnClickListener { webView.goForward() }
            }
            toolbar.addView(forwardButton)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)

        webView.addJavascriptInterface(JsBridge(), BRIDGE_NAME)
        webView.webViewClient = BrowserNavigationClient()
        webView.webChromeClient = BrowserChromeClient()

        installPostMessageShim()
    }

    private fun installPostMessageShim() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, POST_MESSAGE_SHIM, setOf("*"))
        }
        // Fallback is handled in onPageStarted
    }

    private fun loadInitialUrl() {
        val headers = options.headers ?: emptyMap()
        webView.loadUrl(options.url, headers)
    }

    private fun updateUI() {
        titleLabel.text = options.title ?: webView.title ?: ""
        if (options.showUrlBar) {
            urlLabel.text = webView.url?.let { Uri.parse(it).host } ?: ""
        }
        backButton?.let {
            it.isEnabled = webView.canGoBack()
            it.setTextColor(if (it.isEnabled) Color.BLACK else Color.GRAY)
        }
        forwardButton?.let {
            it.isEnabled = webView.canGoForward()
            it.setTextColor(if (it.isEnabled) Color.BLACK else Color.GRAY)
        }
    }

    fun executeScript(code: String, completion: (Result<String>) -> Unit) {
        val id = UUID.randomUUID().toString()
        pendingScripts[id] = completion
        val wrapped = """
            (async () => {
                try {
                    const __r = await (async () => { $code })();
                    ${BRIDGE_NAME}.resolveScript(${JSONObject.quote(id)}, JSON.stringify(__r === undefined ? null : __r));
                } catch (e) {
                    ${BRIDGE_NAME}.rejectScript(${JSONObject.quote(id)}, String(e && e.message ? e.message : e));
                }
            })();
        """
        webView.evaluateJavascript(wrapped, null)
    }

    fun insertCSS(code: String, completion: (Result<Unit>) -> Unit) {
        val script = """
            (function(){
                var s = document.createElement('style');
                s.textContent = ${JSONObject.quote(code)};
                document.head.appendChild(s);
            })();
        """
        webView.evaluateJavascript(script) { completion(Result.success(Unit)) }
    }

    fun addUserScript(code: String, atDocumentStart: Boolean): Boolean {
        if (atDocumentStart) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                return false
            }
            val handler = WebViewCompat.addDocumentStartJavaScript(webView, code, setOf("*"))
            documentStartHandlers.add(handler)
        } else {
            documentEndScripts.add(code)
        }
        return true
    }

    fun removeAllUserScripts() {
        documentEndScripts.clear()
        documentStartHandlers.forEach { it.remove() }
        documentStartHandlers.clear()
    }

    fun cleanup() {
        pendingScripts.values.forEach {
            it(Result.failure(RuntimeException("Browser closed")))
        }
        pendingScripts.clear()
        removeAllUserScripts()
        mainHandler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        CookieManager.getInstance().flush()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private inner class JsBridge {
        @JavascriptInterface
        fun postMessage(data: String) {
            mainHandler.post { delegate?.onMessage(data) }
        }

        @JavascriptInterface
        fun resolveScript(id: String, result: String) {
            mainHandler.post {
                pendingScripts.remove(id)?.invoke(Result.success(result))
            }
        }

        @JavascriptInterface
        fun rejectScript(id: String, error: String) {
            mainHandler.post {
                pendingScripts.remove(id)?.invoke(Result.failure(RuntimeException(error)))
            }
        }
    }

    private fun showSafely(builder: AlertDialog.Builder, onFail: () -> Unit) {
        try {
            builder.show()
        } catch (_: WindowManager.BadTokenException) {
            onFail()
        } catch (_: IllegalStateException) {
            onFail()
        }
    }

    private inner class BrowserNavigationClient : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            progressBar.visibility = View.VISIBLE
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(POST_MESSAGE_SHIM, null)
            }
            delegate?.onLoadStart(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            progressBar.visibility = View.GONE
            updateUI()
            CookieManager.getInstance().flush()
            for (script in documentEndScripts) {
                view.evaluateJavascript(script, null)
            }
            delegate?.onLoadStop(url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            progressBar.visibility = View.GONE
            delegate?.onLoadError(
                request.url.toString(),
                error.errorCode,
                error.description?.toString() ?: ""
            )
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase() ?: ""
            if (scheme !in EXTERNAL_SCHEMES) return false
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: ActivityNotFoundException) { }
            return true
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val builder = AlertDialog.Builder(hostActivity)
                .setTitle("Certificate Error")
                .setMessage("The certificate for ${error.url} is not valid. Do you want to continue?")
                .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
                .setPositiveButton("Continue") { _, _ -> handler.proceed() }
                .setOnCancelListener { handler.cancel() }
            showSafely(builder) { handler.cancel() }
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String
        ) {
            val container = LinearLayout(hostActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            val userField = EditText(hostActivity).apply {
                hint = "Username"
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val passField = EditText(hostActivity).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            container.addView(userField)
            container.addView(passField)

            val builder = AlertDialog.Builder(hostActivity)
                .setTitle("Authentication Required")
                .setMessage("Log in to $host")
                .setView(container)
                .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
                .setPositiveButton("Log In") { _, _ ->
                    handler.proceed(userField.text.toString(), passField.text.toString())
                }
                .setOnCancelListener { handler.cancel() }
            showSafely(builder) { handler.cancel() }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val message = if (detail.didCrash()) "WebView renderer crashed" else "WebView renderer killed by system"
            delegate?.onLoadError(view.url ?: "", -1, message)
            delegate?.onCloseRequested()
            return true
        }
    }

    private inner class BrowserChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (options.title == null) titleLabel.text = title ?: ""
        }

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            val builder = AlertDialog.Builder(hostActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
            showSafely(builder) { result.cancel() }
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            val builder = AlertDialog.Builder(hostActivity)
                .setMessage(message)
                .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
            showSafely(builder) { result.cancel() }
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String?,
            result: JsPromptResult
        ): Boolean {
            val input = EditText(hostActivity).apply {
                setText(defaultValue ?: "")
            }
            val builder = AlertDialog.Builder(hostActivity)
                .setMessage(message)
                .setView(input)
                .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                .setOnCancelListener { result.cancel() }
            showSafely(builder) { result.cancel() }
            return true
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val throwaway = WebView(view.context).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        view.loadUrl(request.url.toString())
                        v.post { v.stopLoading(); v.destroy() }
                        return true
                    }
                }
            }
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = throwaway
            resultMsg.sendToTarget()
            return true
        }
    }
}
