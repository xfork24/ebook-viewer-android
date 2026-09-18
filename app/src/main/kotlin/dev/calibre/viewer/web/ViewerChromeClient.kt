// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Chrome-side glue: routes document.title pings into the bridge (the JS half
// of the protocol lives inside viewer.js) and mirrors console messages into
// logcat like upstream javaScriptConsoleMessage does to stderr — on e-ink
// devices without chrome://inspect this is the only console you get.

package dev.calibre.viewer.web

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import dev.calibre.viewer.bridge.ViewerBridge

class ViewerChromeClient(private val bridge: ViewerBridge) : WebChromeClient() {

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (title != null) bridge.onTitleChanged(title)
    }

    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
        message ?: return false
        val tag = "ViewerJS"
        val text = "${message.sourceId()}:${message.lineNumber()}: ${message.message()}"
        when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.DEBUG -> Log.d(tag, text)
            ConsoleMessage.MessageLevel.ERROR -> Log.e(tag, text)
            ConsoleMessage.MessageLevel.LOG -> Log.i(tag, text)
            ConsoleMessage.MessageLevel.TIP -> Log.i(tag, text)
            ConsoleMessage.MessageLevel.WARNING -> Log.w(tag, text)
        }
        return true
    }
}
