// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre.utils.filenames.path_from_root as used by the viewer URL
// handler: resolve a POSIX-style book name under the rendered book directory,
// rejecting absolute paths, traversal components and empty components. Book
// names come straight out of the manifest we generated, but the URL path is
// attacker-visible input, so the containment check is mandatory.

package dev.calibre.viewer.serve

import java.io.File

object SafeBookPath {

    /** Resolve [name] under [root]; null when the name escapes [root]. */
    fun resolve(root: File, name: String): File? {
        if (name.isEmpty()) return null
        // An absolute name ("/etc/passwd") splits to an empty first component
        // and is rejected below, like upstream's os.path.isabs check.
        val parts = name.replace('\\', '/').split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        val rootCanonical = root.canonicalFile
        val target = File(rootCanonical, parts.joinToString("/")).canonicalFile
        val prefix = rootCanonical.path + File.separator
        if (!target.path.startsWith(prefix)) return null
        return target
    }
}
