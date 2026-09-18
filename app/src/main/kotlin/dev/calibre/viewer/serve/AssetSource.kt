// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Byte-source for the bundled viewer assets (index.html, viewer.js(.map),
// pdf-mathjax-loader.js, mathjax/…). Abstracted behind an interface so the
// router is unit-testable on the JVM; AndroidAssetSource wraps AssetManager.

package dev.calibre.viewer.serve

fun interface AssetSource {

    /**
     * Read an asset relative to the viewer/ asset root ("viewer.js",
     * "mathjax/es5/startup.js"); null when the asset does not exist. The
     * name has already passed traversal checks — implementations may assume
     * it is a safe relative path.
     */
    fun read(assetPath: String): ByteArray?
}
