// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The EPUBInput.convert() port with for_viewer=True semantics (the only mode
// the viewer uses): extract, locate the OPF, handle font obfuscation / DRM
// detection, make hrefs root-relative, rationalize covers, convert an EPUB 3
// nav document to NCX, clean the spine, and write a normalized content.opf
// at the book root. Mirrors calibre/ebooks/conversion/plugins/epub_input.py.

package dev.calibre.viewer.parser.epub

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.dom.XmlWriter
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile

private const val ADOBE_OBFUSCATION = "http://ns.adobe.com/pdf/enc#RC"
private const val IDPF_OBFUSCATION = "http://www.idpf.org/2008/embedding"
private const val XML_LANG_NS = "http://www.w3.org/XML/1998/namespace"

private val PAGE_TEMPLATE_MIMES = setOf(
    "application/vnd.adobe-page-template+xml",
    "application/vnd.adobe.page-template+xml",
    "application/adobe-page-template+xml",
    "application/adobe.page-template+xml",
    "application/text",
)

private const val KOBO_SPAN_CLASS = "koboSpan"

class EpubInput(private val parser: DomParser, private val log: (String) -> Unit = {}) {

    /**
     * Extract [epubFile] into [tdir] and normalize it. Returns the canonical
     * name of the generated root OPF ("content.opf").
     */
    fun convert(epubFile: File, tdir: File, isKepub: Boolean = false): String {
        val nameToFile = extractZip(epubFile, tdir)

        val opfName = findOpf(nameToFile)
            ?: throw NotEpubException("${epubFile.path} is not a valid EPUB file (could not find opf)")

        val opf = OpfDocument.parse(nameToFile.getValue(opfName).readBytes(), parser)
        opf.unquoteUrls()

        if (nameToFile.containsKey("META-INF/encryption.xml")) {
            if (!processEncryption(tdir, nameToFile, opf)) {
                throw DrmException(epubFile.name)
            }
        }

        // hrefs become root-relative when the OPF is not at the book root
        opf.rewriteHrefsForOpfLocation(opfName)

        if (opf.packageVersion >= 3.0) rationalizeCover3(opf) else rationalizeCover2(opf)

        val navName = epub3NavName(opf, nameToFile)
        if (navName != null) convertEpub3Nav(opf, nameToFile, navName)

        for (item in opf.manifestItems()) {
            if (item.getAttr("media-type") == "application/x-dtbook+xml") {
                throw InvalidBookException("EPUB files with DTBook markup are not supported")
            }
        }

        val notForSpine = HashSet<String>()
        for (item in opf.manifestItems()) {
            val id = item.getAttr("id") ?: continue
            val mt = item.getAttr("media-type")
            if (mt in PAGE_TEMPLATE_MIMES) {
                notForSpine.add(id)
                continue
            }
            val ext = item.getAttr("href")?.substringAfterLast('.')?.lowercase()
            if (mt == "text/plain" && (ext == "otf" || ext == "ttf")) {
                // some epub authoring software sets font mime types to text/plain
                notForSpine.add(id)
                item.setAttr("media-type", "application/font")
            }
        }

        // drop itemrefs with a missing/duplicate idref or a non-spine mime
        val seen = HashSet<String>()
        for (ref in opf.spineItemrefs()) {
            val idref = ref.getAttr("idref")
            if (idref.isNullOrEmpty() || idref in notForSpine || !seen.add(idref)) {
                opf.root.parentOf(ref)?.remove(ref)
            }
        }

        if (opf.spineItemrefs().isEmpty()) {
            throw InvalidBookException("No valid entries in the spine of this EPUB")
        }

        if (isKepub) checkForKoboDrm(opf, nameToFile)

        val out = File(tdir, "content.opf")
        out.writeBytes(renderOpf(opf))
        nameToFile["content.opf"] = out
        return "content.opf"
    }

    // ---- extraction -------------------------------------------------------

    private fun extractZip(epubFile: File, tdir: File): MutableMap<String, File> {
        val nameToFile = LinkedHashMap<String, File>()
        try {
            ZipFile(epubFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    // zipfile semantics: drop leading '/', '', '.' and '..' segments
                    val name = entry.name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                        .joinToString("/")
                    if (name.isEmpty()) continue
                    val dest = File(tdir, name)
                    if (!isInside(tdir, dest)) continue
                    dest.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    nameToFile[name] = dest
                }
            }
        } catch (e: ZipException) {
            throw InvalidBookException("EPUB appears to be invalid ZIP file: ${epubFile.name}")
        } catch (e: IOException) {
            throw InvalidBookException("Could not read EPUB file: ${epubFile.name}")
        }
        return nameToFile
    }

    private fun isInside(root: File, f: File): Boolean {
        val rootPath = root.canonicalPath + File.separator
        return f.canonicalPath.startsWith(rootPath)
    }

    /** find_opf(): container.xml rootfile, else a filesystem scan for .opf. */
    private fun findOpf(nameToFile: Map<String, File>): String? {
        val container = nameToFile["META-INF/container.xml"]
        if (container != null && container.length() > 0) {
            try {
                val root = parser.parseXml(container.readBytes())
                for (r in root.walk().filterIsInstance<DomElement>()) {
                    if (r.localName != "rootfile") continue
                    val mt = attrWithSuffix(r, "media-type") ?: continue
                    if (mt != "application/oebps-package+xml") continue
                    val path = attrWithSuffix(r, "full-path") ?: continue
                    val name = path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                        .joinToString("/")
                    val f = nameToFile[name]
                    if (f != null && f.length() > 0) return name
                }
            } catch (e: Exception) {
                log("container.xml parse failed: ${e.message}")
            }
        }
        return nameToFile.keys.asSequence()
            .filter { it.lowercase().endsWith(".opf") }
            .filter { !it.contains("__MACOSX") }
            .filter { !it.substringAfterLast('/').startsWith(".") }
            .firstOrNull()
    }

    private fun attrWithSuffix(e: DomElement, suffix: String): String? {
        for (key in e.attrs.keys) {
            if (key.substringAfter('}') == suffix) return e.getAttr(key)
        }
        return null
    }

    // ---- encryption -------------------------------------------------------

    /**
     * process_encryption(): returns false when the book carries real DRM
     * (caller raises DrmException). Obfuscated fonts are decrypted in place.
     */
    private fun processEncryption(
        tdir: File,
        nameToFile: MutableMap<String, File>,
        opf: OpfDocument,
    ): Boolean {
        val idpfKey = opf.rawUniqueIdentifier()
            ?.replace(Regex("[ \\t\\r\\n\\f\\u000B]"), "") // Python \s covers \f and \v too
            ?.toByteArray(Charsets.UTF_8)
            ?.let { sha1(it) }

        var key: ByteArray? = null
        for (item in opf.identifiers()) {
            var scheme: String? = null
            for (attrName in item.attrs.keys) {
                if (attrName.substringAfter('}') == "scheme") scheme = item.getAttr(attrName)
            }
            val text = item.text
            if ((scheme?.lowercase() == "uuid") || (text != null && text.startsWith("urn:uuid:"))) {
                key = text?.substringAfterLast(':')?.let {
                    runCatching { UUID.fromString(it).let { u -> longsToBytes(u) } }.getOrNull()
                }
            }
        }

        val encFile = nameToFile.getValue("META-INF/encryption.xml")
        val root = try {
            parser.parseXml(encFile.readBytes())
        } catch (e: Exception) {
            log("encryption.xml parse failed: ${e.message}")
            return false
        }

        val parentOf = HashMap<DomElement, DomElement>()
        for ((parent, node) in root) {
            if (node is DomElement && parent != null) parentOf[node] = parent
        }
        for (em in root.walk().filterIsInstance<DomElement>().filter { it.localName == "EncryptionMethod" }) {
            val algorithm = em.getAttr("Algorithm") ?: ""
            if (algorithm != ADOBE_OBFUSCATION && algorithm != IDPF_OBFUSCATION) return false
            val scope = parentOf[em] ?: continue
            val cr = scope.walk().filterIsInstance<DomElement>()
                .firstOrNull { it.localName == "CipherReference" } ?: continue
            val uri = cr.getAttr("URI") ?: continue
            // upstream urlunquote()s the URI before resolving it against tdir
            val name = BookNames.unquote(uri).split('/')
                .filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
            val target = nameToFile[name] ?: continue
            val tkey = if (algorithm == ADOBE_OBFUSCATION) key else idpfKey
            if (tkey != null && target.length() > 0) {
                target.writeBytes(decryptFontData(tkey, target.readBytes(), algorithm))
            }
        }
        return true
    }

    private fun longsToBytes(u: UUID): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(16)
        bb.putLong(u.mostSignificantBits)
        bb.putLong(u.leastSignificantBits)
        return bb.array()
    }

    private fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    private fun decryptFontData(key: ByteArray, data: ByteArray, algorithm: String): ByteArray {
        val isAdobe = algorithm == ADOBE_OBFUSCATION
        val cryptLen = if (isAdobe) 1024 else 1040
        val crypt = data.copyOf(minOf(cryptLen, data.size))
        for (i in crypt.indices) {
            crypt[i] = (crypt[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return crypt + data.copyOfRange(minOf(cryptLen, data.size), data.size)
    }

    // ---- covers (rationalize_cover2/3, for_viewer semantics) ---------------

    /** opf2.raster_cover. */
    private fun rasterCover(opf: OpfDocument): String? {
        val coverMetas = opf.metaElements("cover").filter { it.getAttr("content") != null }
        if (coverMetas.isNotEmpty()) {
            val coverId = coverMetas.first().getAttr("content") ?: return null
            val items = opf.manifestItems()
            for (item in items) {
                if (item.getAttr("id") == coverId) {
                    val mt = item.getAttr("media-type") ?: ""
                    if (mt.isNotEmpty() && "xml" !in mt && "html" !in mt) return item.getAttr("href")
                }
            }
            for (item in items) {
                if (item.getAttr("href") == coverId) {
                    val mt = item.getAttr("media-type") ?: ""
                    if (mt.isNotEmpty() && "xml" !in mt && "html" !in mt) return item.getAttr("href")
                }
            }
        } else if (opf.packageVersion >= 3.0) {
            return epub3RasterCover(opf)
        }
        return null
    }

    /** opf2.epub3_raster_cover. */
    private fun epub3RasterCover(opf: OpfDocument): String? {
        val items = opf.manifestItems()
        val idMap = HashMap<String, DomElement>()
        for (item in items) {
            val id = item.getAttr("id") ?: ""
            idMap[id] = item
            val props = item.getAttr("properties")?.lowercase()?.split(' ')?.toSet() ?: emptySet()
            if ("cover-image" in props) {
                val mt = item.getAttr("media-type") ?: ""
                if (mt.isNotEmpty() && "xml" !in mt && "html" !in mt) return item.getAttr("href")
            }
        }
        // "Open" Manga Format files have the cover as the first item in the spine
        val spine = opf.spineItemrefs()
        if (spine.isNotEmpty()) {
            val man = idMap[spine[0].getAttr("idref") ?: ""]
            val mt = man?.getAttr("media-type")
            if (mt != null && mt.lowercase() in setOf("image/jpeg", "image/jpg", "image/png", "image/webp")) {
                return man.getAttr("href")
            }
        }
        return null
    }

    private fun rationalizeCover3(opf: OpfDocument) {
        // Look for titlepages incorrectly marked in the guide as covers
        var guideCoverHref: String? = null
        var guideTitlepageHref: String? = null
        var guideTitlepageId: String? = null
        for (ref in opf.guideReferences()) {
            if (ref.getAttr("type")?.lowercase() == "cover") {
                guideCoverHref = ref.getAttr("href")?.substringBefore('#') ?: ""
                break
            }
        }
        if (!guideCoverHref.isNullOrEmpty()) {
            val spine = opf.spineItemrefs()
            if (spine.isNotEmpty()) {
                val idref = spine[0].getAttr("idref") ?: ""
                for (x in opf.manifestItems()) {
                    if (x.getAttr("id") == idref && x.getAttr("href") == guideCoverHref) {
                        guideTitlepageHref = guideCoverHref
                        guideTitlepageId = idref
                        break
                    }
                }
            }
        }

        val rasterCoverHref = epub3RasterCover(opf) ?: rasterCover(opf)
        if (rasterCoverHref != null) {
            opf.setGuideType("cover", rasterCoverHref, "Cover image")
        }
        var titlepageId: String? = null
        var titlepageHref: String? = null
        for (item in opf.itemsWithProperty("calibre:title-page")) {
            val tid = item.getAttr("id")
            val href = item.getAttr("href")
            if (!href.isNullOrEmpty() && !tid.isNullOrEmpty()) {
                titlepageId = tid
                titlepageHref = href.substringBefore('#')
                break
            }
        }
        if (titlepageHref == null) {
            titlepageHref = guideTitlepageHref
            titlepageId = guideTitlepageId
        }
        if (titlepageHref != null) {
            opf.setGuideType("titlepage", titlepageHref, "Cover page")
            val spine = opf.spineItemrefs()
            if (spine.size > 1) {
                for (item in spine) {
                    if (item.getAttr("idref") == titlepageId) {
                        log("Found HTML cover $titlepageHref")
                        item.removeAttr("linear")
                        return
                    }
                }
            }
        }
    }

    private fun rationalizeCover2(opf: OpfDocument) {
        var guideCover: String? = null
        var guideElem: DomElement? = null
        for (ref in opf.guideReferences()) {
            if (ref.getAttr("type")?.lowercase() == "cover") {
                guideCover = ref.getAttr("href")?.substringBefore('#') ?: ""
                break
            }
            guideElem = ref
        }
        if (guideCover.isNullOrEmpty()) {
            val rc = rasterCover(opf)
            if (rc != null) {
                val g: DomElement = if (guideElem == null) {
                    DomElement("{$OPF_NS}guide").also { opf.root.append(it) }
                } else {
                    opf.root.parentOf(guideElem) ?: opf.root
                }
                guideCover = rc
                val ref = DomElement("{$OPF_NS}reference")
                ref.setAttr("href", rc)
                ref.setAttr("type", "cover")
                g.append(ref)
                guideElem = ref
            }
            return
        }
        val spine = opf.spineItemrefs()
        if (spine.isEmpty()) return
        val idref = spine[0].getAttr("idref") ?: ""
        val elem = opf.manifestItems().firstOrNull { it.getAttr("id") == idref }
        if (elem == null || elem.getAttr("href") != guideCover) return
        log("Found HTML cover $guideCover")

        // for_viewer: the cover stays in the spine, but always first+linear
        spine[0].removeAttr("linear")

        val rc = rasterCover(opf)
        // the guide cover entry moves to the raster cover; the html cover
        // becomes the titlepage entry
        val coverGuideElem = guideReferencesOfType(opf, "cover").firstOrNull()
        if (rc != null) {
            coverGuideElem?.setAttr("href", rc)
        } else {
            // desktop renders an SVG cover to calibre_raster_cover.jpg here;
            // on Android the reference simply points at a missing file, which
            // the downstream cover detection ignores (never in name_path_map)
            coverGuideElem?.setAttr("href", "calibre_raster_cover.jpg")
            opf.createManifestItem("calibre_raster_cover.jpg", "image/jpeg")
                .setAttr("id", "calibre_raster_cover")
        }
        opf.setGuideType("titlepage", guideCover, "Cover page")
    }

    private fun guideReferencesOfType(opf: OpfDocument, gtype: String): List<DomElement> =
        opf.guideReferences().filter { it.getAttr("type")?.lowercase() == gtype }

    // ---- EPUB 3 nav -> NCX --------------------------------------------------

    /** opf2.epub3_nav: the name of the nav document, or null. */
    private fun epub3NavName(opf: OpfDocument, nameToFile: Map<String, File>): String? {
        if (opf.packageVersion < 3.0) return null
        for (item in opf.manifestItems()) {
            val props = item.getAttr("properties")?.lowercase()?.split(' ') ?: emptyList()
            if ("nav" !in props) continue
            val mt = item.getAttr("media-type") ?: ""
            if ("html" in mt.lowercase()) {
                val href = item.getAttr("href") ?: continue
                val name = BookNames.hrefToName(href, null) ?: continue
                val f = nameToFile[name]
                if (f != null && f.length() > 0) return name
            }
        }
        return null
    }

    private fun convertEpub3Nav(opf: OpfDocument, nameToFile: MutableMap<String, File>, navName: String) {
        val root = parser.parseHtml(nameToFile.getValue(navName).readBytes())

        val ncx = DomElement("{$NCX_NS}ncx")
        ncx.setAttr("version", "2005-1")
        ncx.setAttr("{$XML_LANG_NS}lang", "eng")
        val navMap = DomElement("{$NCX_NS}navMap")
        ncx.append(navMap)

        val epubType = "{$EPUB_NS}type"
        var processed = false
        for (nav in root.walk().filterIsInstance<DomElement>().filter { it.localName == "nav" }) {
            if (nav.getAttr(epubType) == "toc") {
                val ol = nav.children.filterIsInstance<DomElement>()
                    .firstOrNull { it.localName == "ol" }
                if (ol != null) processNavNode(ol, navMap, navName)
                processed = true
                break
            }
        }
        if (!processed) return

        val dir = BookNames.dirname(navName)
        val ncxName = generateName(nameToFile, if (dir.isEmpty()) "" else "$dir/", "nav-generated", ".ncx")
        val f = File(nameToFile.getValue(navName).parentFile, BookNames.basename(ncxName))
        f.writeBytes(XmlWriter.serializeToBytes(ncx))
        nameToFile[ncxName] = f

        val item = opf.createManifestItem(ncxName, MimeTypes.NCX)
        val tocId = item.getAttr("id") ?: ""
        for (spine in opf.spineElements()) {
            spine.setAttr("toc", tocId)
        }
    }

    private fun generateName(nameToFile: Map<String, File>, prefix: String, base: String, suffix: String): String {
        var c = 0
        var name = "$prefix$base$suffix"
        while (name in nameToFile) {
            c += 1
            name = "$prefix$base-$c$suffix"
        }
        return name
    }

    private fun processNavNode(ol: DomElement, parent: DomElement, navName: String) {
        for (li in ol.children.filterIsInstance<DomElement>().filter { it.localName == "li" }) {
            val np = addFromLi(li, parent, navName)
            val nested = li.children.filterIsInstance<DomElement>().firstOrNull { it.localName == "ol" }
            if (np != null && nested != null) {
                processNavNode(nested, np, navName)
            }
        }
    }

    private fun addFromLi(li: DomElement, parent: DomElement, navName: String): DomElement? {
        var href: String? = null
        var text: String? = null
        for (x in li.children.filterIsInstance<DomElement>()) {
            if (x.localName != "a" && x.localName != "span") continue
            text = xmlTextOf(x).trim().ifEmpty {
                x.walk().filterIsInstance<DomElement>()
                    .mapNotNull { it.getAttr("title") }.joinToString(" ").trim()
            }
            val h = x.getAttr("href")
            if (!h.isNullOrEmpty()) {
                href = if (h.startsWith("#")) BookNames.basename(navName) + h else h
            }
            break
        }
        val np = DomElement("{$NCX_NS}navPoint")
        parent.append(np)
        val label = DomElement("{$NCX_NS}navLabel")
        np.append(label)
        val textElem = DomElement("{$NCX_NS}text")
        label.append(textElem)
        textElem.text = text
        if (href != null) {
            val content = DomElement("{$NCX_NS}content")
            content.setAttr("src", href)
            np.append(content)
        }
        return np
    }

    /** lxml tostring(method='text') equivalent: subtree text without own tail. */
    private fun xmlTextOf(e: DomElement): String {
        val sb = StringBuilder()
        fun emit(el: DomElement) {
            el.text?.let { sb.append(it) }
            for (child in el.children) {
                if (child is DomElement) emit(child)
                child.tail?.let { sb.append(it) }
            }
        }
        emit(e)
        return sb.toString()
    }

    // ---- kepub -------------------------------------------------------------

    /** kepubify.check_for_kobo_drm() (the viewer's for_viewer branch). */
    private fun checkForKoboDrm(opf: OpfDocument, nameToFile: Map<String, File>) {
        val rights = nameToFile["rights.xml"]
        if (rights == null || rights.length() == 0L) return
        val spineNames = spineHtmlNames(opf, nameToFile)
        val first = spineNames.firstOrNull() ?: return
        val head = ByteArray(8192)
        var off = 0
        first.inputStream().use { input ->
            while (off < head.size) {
                val n = input.read(head, off, head.size - off)
                if (n < 0) break
                off += n
            }
        }
        val raw = head.copyOf(off)
        val asText = String(raw, Charsets.ISO_8859_1)
        if ("<?xml" !in asText && "<html" !in asText && KOBO_SPAN_CLASS !in asText) {
            throw DrmException("Kobo DRM")
        }
    }

    private fun spineHtmlNames(opf: OpfDocument, nameToFile: Map<String, File>): List<File> {
        val idToName = HashMap<String, File>()
        for (item in opf.manifestItems()) {
            val id = item.getAttr("id") ?: continue
            val href = item.getAttr("href") ?: continue
            val name = BookNames.hrefToName(href, null) ?: continue
            nameToFile[name]?.let { idToName[id] = it }
        }
        val ans = ArrayList<File>()
        for (ref in opf.spineItemrefs()) {
            val idref = ref.getAttr("idref") ?: continue
            idToName[idref]?.let { ans.add(it) }
        }
        return ans
    }
}
