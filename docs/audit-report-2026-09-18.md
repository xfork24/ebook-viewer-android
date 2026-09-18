# book-parser 全量代码审核与修复报告

**日期**：2026-09-18
**范围**：`book-parser/src/main` 全部 4 个包（`dom/` `transform/` `epub/` 及根），共约 20 个源文件
**方法**：逐文件、逐函数对照上游 calibre 源码（`~/workspace/calibre`）——不对照记忆，直接读上游实现核对语义；对无法静态判定的（数值格式化），用 C++/Python 原生实现生成 31 万条 golden fixture 对拍
**结论**：发现并修复 **4 类严重缺陷、16 处行为偏差**；修复后 130 项单测全绿（0 失败/0 跳过），`:app:assembleDebug` 构建成功

---

## 一、严重缺陷（会导致功能错误）

### A1. `CssTransformer.formatGeneralCssDouble` 数值格式化整体错误 [#13]

**问题**：`fast_css_transform.cpp:522` 调用 `std::to_chars(txt, end, new_val, std::chars_format::general)` 重写所有相对字体尺寸。原 Kotlin 实现用 `Double.toString()` 取数字 + `%g` 风格阈值，两处都不对：

- JDK 17 的 `Double.toString()` **不是**最短往返表示（pre-Ryū），某些值会产生多余数字；
- to_chars(general) 的定点/科学分界是**常数阈值** `-4 ≤ X < 6`（X 为首位数字指数），而非 printf `%g` 的 `X < 有效位数`。

**修复**（`CssTransformer.kt`）：

1. `shortestDecimalDigits()`：`BigDecimal(double 精确值).round(MathContext(p, HALF_EVEN))`，p 从 1 到 17 递增，去尾零后做往返测试 `"$digits e ${exp}" == a`——与 libc++ Ryu 最短数字算法完全一致；
2. 分界用常数阈值 `x in -4..5` 定点、否则科学计数 `D[.DDD]e±XX`（指数 ≥2 位带符号）；
3. 符号位用 `doubleToRawLongBits(d) < 0`（区分 `-0.0` 与 `0.0`）；inf → `inf`/`-inf`；NaN → `nan`。

**验证**：`tools/gen_double_fixtures.cpp` 用本机 clang++/libc++ 生成 310,056 条 fixture（30 万随机位型 + X∈[-300,300]×P∈[1,17] 结构化 + 特殊值），`FormatDoubleParityTest` 逐条对拍 **100% 一致**（4.4s）。期间还发现并排除了一个数据陷阱：libc++ 的**无格式** to_chars 重载与显式 `general` 重载行为不同（大值时一个定点一个科学），上游用的是后者，早先的错误数据已全部作废。

### A2. `EpubInput` 字体混淆解密的两个 key 缺陷 [#14]

1. `CipherReference/@URI` 漏 `urlunquote`：含 `%20` 等编码的字体文件名解析失败 → 该字体不解密 → 全书字体回退。已补 `BookNames.unquote(uri)`。
2. IDPF key 的空白剥离正则 `[ \t\r\n]` 漏了 Python `\s` 中的 `\f\v`：identifier 含这两个字符时 SHA-1 key 不同 → 解密输出乱码。已补全。

### A3. `Toc.itemAtTop` 遍历集合错误 [#14]

上游是 `body.iterdescendants()`（**不含 body 自身**），Kotlin 写成了 `body.walk()`（含自身）→ body 有前置直接文本时会误判"标题不在页首"→ 多余生成 `toc_N` id → 不必要的文件写回与锚点污染。已改为 `body.descendants()`。

### A4. `manifestIdMap`/`manifestTypeMap` 丢弃 null 值 [#14]

上游（`polish/container.py:703-708` 等）对 `href_to_name()` 的结果**无条件**收录——href 无法解析时 map 里是 `None`（键存在/列表含 null）。Kotlin 原来用 `?: continue` 丢掉：

- `manifest_type_map[ncx][0]` 为 None 时上游返回 None（放弃 NCX），Kotlin 却会取列表中**下一个**条目——对同一本书产生不同 TOC 来源；
- 与已修复的 `manifestItemsWithProperty`（`find_cover_image3` 的 first-yield-even-None 语义）不一致。

已改为 `Map<String, String?>` / `Map<String, List<String?>>`，值可空，消费端行为与上游逐点一致。

---

## 二、行为偏差修复（bug-for-bug 对齐）

| # | 位置 | 问题 → 修复 |
|---|------|------------|
| B1 | `Toc.parseNcx` pagelist | 上游 `if pagenum:` / `if href:` 是 **truthy** 检查（空串跳过），Kotlin 只判 null → 空串会追加 `dest=null` 条目。两处均补 `takeIf { isNotEmpty() }` |
| B2 | `Toc.getNavLandmarks` | 上游仅当 href **truthy** 才 `break`；空 href 应继续扫下一个 `<a>`，Kotlin 空串会 break。已修 |
| B3 | `Toc.ensureId` | 上游 `if elem_id:` / `if anchor:`——`id=""` 应视为缺失并生成 `toc_N`，Kotlin 原样返回空串。已改 `isNullOrEmpty` |
| B4 | `CoverFinder` cover 检测 | EPUB2 `<meta name="cover">` 需要两个**不同**的 OPF 视图：polish container 的 XPath 是**大小写敏感**（`@name="cover"`），opf2 的 `raster_cover_path` 是 `re:match("i")` 大小写不敏感。原实现只有一个宽松视图。已在 `OpfDocument` 增加 `metaElementsExact()`（严格视图）供 CoverFinder 使用，`metaElements()`（宽松）留给 EpubInput |
| B5 | `LengthCounter` | `speedup.c count_chars` 只在 tag 是字符串（元素）时取 `.text`——注释体（lxml 存在 `.text`）**不计数**。原实现对 DomComment 计数。已用 `node is DomElement` 门控 |
| B6 | `HtmlTransformer` 链接属性 | `render_book.py handle_link` 的 data 属性是 `json.dumps(..., ensure_ascii=False)` **默认分隔符**——含 `": "` `", "` 空格。原实现输出紧凑 JSON。已对齐（5 处断言同步修正） |
| B7 | `HtmlTransformer` 缺失目标 | 上游 missing 分支只设 data 属性、**不写** `link_to_map`。已对齐（测试改为断言缺失） |
| B8 | `SmilTransformer.parseSmilTime` | 上游先查 `endswith('s')` 再查 `endswith('ms')` → `"123ms"` 走 `float("123m")` 崩溃（上游死分支）。保留 bug-for-bug，测试改为 `assertFailsWith` |
| B9 | `CssTransformer.commitTokens` 截断 | u32string 槽位 vs Kotlin UTF-16：token 首字符为增补平面字符时 `out.setLength(pos-1)` 会劈开代理对。已加低代理回溯 |
| B10 | `CssTransformer.readInteger` | `Char.isDigit()` 接受非 ASCII 数字，上游 C 代码只认 `0-9`。两处已改 `in '0'..'9'` |
| B11 | `CoverFinder.collectText` | Python `re \s+` 是 Unicode 语义（NBSP、全角空格）；`RegexOption` 无对应 JDK flag。已直接 `Pattern.compile("\\s+", UNICODE_CHARACTER_CLASS)` |
| B12 | `EpubInput` 加密节点匹配 | `EncryptionMethod`/`CipherReference` 由 `contains` 收紧为 `equals`（上游 lxml `iter(tag)` 是精确匹配；保留跨命名空间宽松，真实文件无影响） |

---

## 三、审计通过、无需修改的部分

- **`BookNames`（路径核心）**：对照 `container.py:102-126` 逐函数核对。`normpath`（含 `..` 折叠与 leading-`..` 保留）、`relpath`（段级公共前缀）、`quote/unquote`（含 `%` 尾部残缺、非法十六进制原样、UTF-8 替换字符语义）、`urlparse` 子集（scheme 判定、netloc、fragment 先于 query）全部等价；`href_to_name` 的"根绝对 href 按 base 相对解析"（上游 `os.path.join` 丢弃空段的 quirk）与"逃逸书根保留 `..`"行为一致。
- **`Toc` 主体**：`to_dict` 的**后序** id 分配（Python 字典字面量求值顺序）、`parse_nav` 的 h1–h6 文档序标题、`from_xpaths` 的空层丢弃+重编号、`parent_for_level` 的 last-child 链下降、`elem_to_toc_text` 的 title/alt 兜底与 1000 字截断——均与上游逐句对应。
- **`EpubInput` 主流程**：extract 的 zipfile 段过滤、find_opf（container.xml rootfile + `__MACOSX`/dotfile 过滤的文件扫描兜底）、delta href 重写 + 根部 `content.opf` 写出、DTBook 拒绝、page-template/font mime 的 not_in_spine、spine 去重、kepub DRM 8KB 探测、nav→NCX 生成（命名冲突检查、spine@toc）。
- **`MimeTypes`**、`OpfDocument` 的 prefix 展开（含 `": "` quirk）、`SmilTransformer` 主体、`DomParser`/`Encoding`/`HtmlNormalizer`/`XmlWriter`（#11 已修，本轮复核无新发现）。

---

## 四、有意保留的偏差（deliberate，需你知悉）

1. **SVG 封面不预渲染 JPEG**：桌面 calibre 在 `rationalize_cover2` 里把 SVG guide cover 渲染成 `calibre_raster_cover.jpg`。Android 侧无等价渲染管线，写入指向缺失文件的 reference；下游 `create_cover_page` 会把 SVG 以 `<img src>` 塞进封面页，**WebView 可直接渲染 SVG**，功能闭环，只是没有预烘焙 JPEG。
2. **畸形 IPv6 URL**：`urlparse("http://[bad")` 上游抛 `ValueError → None`，Kotlin 的解析子集不抛。此类 href 现实书籍几乎不存在，且结果通常同样因 scheme 非空而返回 null。
3. **OPF 元素查询的宽松双视图**：`matches()` 对命名空间（OPF NS 或无 NS）与 localName 大小写宽容，同时服务 polish 严格视图与 opf2 宽松视图。真实 OPF 均为规范小写，两种视图等价；宽容方向只会"多救回"非规范书。cover 检测所需的 case-sensitive `@name` 已通过 `metaElementsExact` 单独保证（B4）。
4. **`item_at_top` 祖先判定**：上游用 `getpath` 字符串前缀判断（理论上 `/p` 会误匹配 `/pp` 前缀），Kotlin 用 identity 祖先集合（更正确）。仅在上游自身理论 bug 的 corner 上行为不同。
5. **`MimeTypes` 扩展表**与 Python `mimetypes` 在极罕见扩展（`.js` 等）上可能不同；OPF manifest 的 `media-type` 刷新会覆盖扩展猜测，实际影响限于 manifest 之外的文件。
6. **`createManifestItem` 无 `<manifest>` 时**抛 NoSuchElementException——上游同样崩（`first()` 无回退），非影响阅读路径。
7. **`identifiers()`/`rawUniqueIdentifier`** 宽松匹配（上游 opf2 的 regex name() 语义）——仅影响混淆字体 key 与元数据展示，已在实现注释说明。

---

## 五、设计目标复核

| 目标 | 状态 |
|------|------|
| 仅复用 viewer.js 渲染核心，预处理层轻量 | ✓ book-parser 纯 JVM，依赖仅 jsoup + kxml2（XmlPullParser 为平台内置），无 Coil/Room/ZIP4J/Hilt |
| 与 calibre 桌面互操作（golden 对拍门禁） | ✓ html_as_json / manifest / JSON 转义语义逐点对齐；双精度格式化有 31 万条对拍设施（`tools/gen_double_fixtures.cpp` + parity test） |
| bug-for-bug 纯度 | ✓ 4 处上游怪癖显式保留并注释（first-yield-None、`123ms` 崩溃、`linear="yes"` 精确匹配、`": "` prefix quirk） |
| 全 EPUB 特性（加密字体/EPUB3 nav/KEPUB/封面） | ✓ 含 DRM 检测拒载与 Kobo DRM 探测 |

**遗留清理项**（不影响功能）：仓库根的 uv Python 骨架（`pyproject.toml` `.python-version` `src/`）尚未删除（计划中已批准替换）；测试代码有 7 处 `createTempDir` deprecated 警告与 1 处冗余 instance 检查警告。

---

## 六、验证状态

- `:book-parser:test`：**130 tests / 0 failures / 0 errors / 0 skipped**（含 310,056 条 `to_chars(general)` golden 对拍、Toc/Cover/EpubInput/Css/Html/Smil/RenderBook 全套）
- `:app:assembleDebug`：BUILD SUCCESSFUL

**下一步（待确认后执行）**：#6 manifest 写出与 RenderBook 编排收尾 → #7-#10 app 层（URL 服务/桥接/Compose 壳/M1 验证）。
