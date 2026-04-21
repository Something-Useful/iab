package com.capacitor.iab

import android.graphics.Color
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "InAppBrowser")
class InAppBrowserPlugin : Plugin(), InAppBrowserDelegate {

    private var browserDialog: InAppBrowserDialog? = null

    @PluginMethod
    fun open(call: PluginCall) {
        val url = call.getString("url")
        if (url.isNullOrEmpty()) {
            call.reject("Invalid or missing 'url'")
            return
        }

        val toolbarColor = call.getString("toolbarColor")?.let { parseHexColor(it) }
        val title = call.getString("title")
        val closeButtonText = call.getString("closeButtonText") ?: "Done"
        val showNav = call.getBoolean("showNavigationButtons") ?: true
        val showUrl = call.getBoolean("showUrlBar") ?: true

        val headers: Map<String, String>? = call.getObject("headers")?.let { obj ->
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                (obj.opt(key) as? String)?.let { map[key] = it }
            }
            map
        }

        val options = InAppBrowserOptions(
            url = url,
            headers = headers,
            toolbarColor = toolbarColor,
            title = title,
            closeButtonText = closeButtonText,
            showNavigationButtons = showNav,
            showUrlBar = showUrl
        )

        activity.runOnUiThread {
            browserDialog?.let { existing ->
                existing.cleanup()
                existing.dismiss()
                browserDialog = null
            }
            val dialog = InAppBrowserDialog(activity, options, this)
            dialog.show()
            browserDialog = dialog
            call.resolve()
        }
    }

    @PluginMethod
    fun close(call: PluginCall) {
        activity.runOnUiThread {
            dismissBrowser()
            call.resolve()
        }
    }

    @PluginMethod
    fun executeScript(call: PluginCall) {
        val code = call.getString("code")
        if (code == null) {
            call.reject("Missing 'code'")
            return
        }
        activity.runOnUiThread {
            val dialog = browserDialog ?: return@runOnUiThread call.reject("No browser open")
            dialog.executeScript(code) { result ->
                result.fold(
                    onSuccess = { call.resolve(JSObject().put("result", it)) },
                    onFailure = { call.reject(it.message ?: "Script error") }
                )
            }
        }
    }

    @PluginMethod
    fun insertCSS(call: PluginCall) {
        val code = call.getString("code")
        if (code == null) {
            call.reject("Missing 'code'")
            return
        }
        activity.runOnUiThread {
            val dialog = browserDialog ?: return@runOnUiThread call.reject("No browser open")
            dialog.insertCSS(code) { result ->
                result.fold(
                    onSuccess = { call.resolve() },
                    onFailure = { call.reject(it.message ?: "CSS error") }
                )
            }
        }
    }

    @PluginMethod
    fun addUserScript(call: PluginCall) {
        val code = call.getString("code")
        if (code == null) {
            call.reject("Missing 'code'")
            return
        }
        val timing = call.getString("injectionTime")
        val atDocumentStart = when (timing) {
            "atDocumentStart" -> true
            "atDocumentEnd" -> false
            else -> {
                call.reject("Invalid injectionTime: must be 'atDocumentStart' or 'atDocumentEnd'")
                return
            }
        }

        activity.runOnUiThread {
            val dialog = browserDialog ?: return@runOnUiThread call.reject("No browser open")
            val ok = dialog.addUserScript(code, atDocumentStart)
            if (ok) call.resolve()
            else call.reject("Document start script injection not supported on this WebView version")
        }
    }

    @PluginMethod
    fun removeAllUserScripts(call: PluginCall) {
        activity.runOnUiThread {
            val dialog = browserDialog ?: return@runOnUiThread call.reject("No browser open")
            dialog.removeAllUserScripts()
            call.resolve()
        }
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        dismissBrowser()
    }

    private fun dismissBrowser() {
        browserDialog?.let { dialog ->
            dialog.cleanup()
            try { dialog.dismiss() } catch (_: Exception) { }
            browserDialog = null
            notifyListeners("exit", JSObject())
        }
    }

    // MARK: - InAppBrowserDelegate

    override fun onLoadStart(url: String) {
        notifyListeners("loadstart", JSObject().put("url", url))
    }

    override fun onLoadStop(url: String) {
        notifyListeners("loadstop", JSObject().put("url", url))
    }

    override fun onLoadError(url: String, code: Int, message: String) {
        notifyListeners(
            "loaderror",
            JSObject().put("url", url).put("code", code).put("message", message)
        )
    }

    override fun onCloseRequested() {
        activity.runOnUiThread { dismissBrowser() }
    }

    override fun onMessage(data: String) {
        notifyListeners("message", JSObject().put("data", data))
    }

    private fun parseHexColor(hex: String): Int? {
        return try {
            var s = hex.trim()
            if (!s.startsWith("#")) s = "#$s"
            Color.parseColor(s)
        } catch (_: Exception) {
            null
        }
    }
}
