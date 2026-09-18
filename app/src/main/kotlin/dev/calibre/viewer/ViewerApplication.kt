// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Placeholder application class referenced by AndroidManifest.xml. The real
// host wiring (WebView creation, bridge setup) lands with the app service
// layer; keeping the class present now means `assembleDebug` stays green
// while the viewer shell is under construction.

package dev.calibre.viewer

import android.app.Application

class ViewerApplication : Application()
