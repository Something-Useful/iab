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

        val headers = call.getObject("headers")?.let { obj ->
            obj.keys().asSequence()
                .mapNotNull { key -> (obj.opt(key) as? String)?.let { key to it } }
                .toMap()
        }

        val options = InAppBrowserOptions(
            url = url,
            headers = headers,
            toolbarColor = call.getString("toolbarColor")?.let { parseHexColor(it) },
            title = call.getString("title"),
            closeButtonText = call.getString("closeButtonText") ?: "Done",
            showNavigationButtons = call.getBoolean("showNavigationButtons") ?: true,
            showUrlBar = call.getBoolean("showUrlBar") ?: true
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
        val code = call.getString("code") ?: return call.reject("Missing 'code'")
        withBrowser(call) { dialog ->
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
        val code = call.getString("code") ?: return call.reject("Missing 'code'")
        withBrowser(call) { dialog ->
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
        val code = call.getString("code") ?: return call.reject("Missing 'code'")
        val atDocumentStart = when (call.getString("injectionTime")) {
            "atDocumentStart" -> true
            "atDocumentEnd" -> false
            else -> return call.reject("Invalid injectionTime: must be 'atDocumentStart' or 'atDocumentEnd'")
        }
        withBrowser(call) { dialog ->
            if (dialog.addUserScript(code, atDocumentStart)) call.resolve()
            else call.reject("Document start script injection not supported on this WebView version")
        }
    }

    @PluginMethod
    fun removeAllUserScripts(call: PluginCall) {
        withBrowser(call) { dialog ->
            dialog.removeAllUserScripts()
            call.resolve()
        }
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        dismissBrowser()
    }

    private fun withBrowser(call: PluginCall, body: (InAppBrowserDialog) -> Unit) {
        activity.runOnUiThread {
            val dialog = browserDialog ?: return@runOnUiThread call.reject("No browser open")
            body(dialog)
        }
    }

    private fun dismissBrowser() {
        val dialog = browserDialog ?: return
        dialog.cleanup()
        if (dialog.isShowing) dialog.dismiss()
        browserDialog = null
        notifyListeners("exit", JSObject())
    }

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
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
