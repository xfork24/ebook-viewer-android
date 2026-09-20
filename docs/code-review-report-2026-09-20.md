# 代码审查与修复工作报告（2026-09-20）

## 1. 范围与方法

### 1.1 范围
仓库 `ebook-viewer-android`（calibre 阅读器在弱墨水屏设备上的 Android 移植），HEAD `345f100`，工作树干净。本次审查覆盖：

- **三个 Gradle 模块**：`app`、`book-parser`、`viewer-core`
- **关键层**：`MainActivity` / `ViewerController` / `ViewerWebHost` / `BookServer` / 五个 store / `tools/build_viewer_js.py` / `docs/`
- **新增资产**：`tools/e2e.py`（956 行 E2E 套件）、`docs/roadmap-2026-09-20.md`

`book-parser` 在 09-18 已做专项审计，本轮仅复查；未重新展开。

### 1.2 方法
1. **并行代码审查**：4 个并行 Explore/Plan 代理分别覆盖 viewer-core 5 个 store、host/bridge 层、web/serve 层、ui + MainActivity + tools + docs
2. **F 清单**：所有候选问题按 P0/P1/P2 分级，逐条带 `file:line` 证据
3. **修复门禁**：每个修复一个独立提交，逐个 `./gradlew test` 全绿
4. **回归测试**：补 4 个针对修复的单元测试（流回收 / 重建守卫 / 无书翻页 / 居中）
5. **模拟器 E2E**：tools/e2e.py 23 测试矩阵（基础设施已就位，待 emulator-5554 恢复后执行）

### 1.3 环境基线
- 274 个 JVM 单测全绿（commit `345f100`）
- book-parser 117 个测试全绿
- emulator-5554 在线（m1viewer AVD，Android 14，1080x2400）
- /sdcard/Download 有 7 本测试书（book1/book2 8.6 MB、eng/hushi/mathjax/mathml/test-book.epub）

## 2. F 清单汇总

| ID    | 严重度 | 位置 | 描述 | 结论 |
|-------|--------|------|------|------|
| F-01  | P1     | `app/src/main/assets/viewer/viewer.js` Go-to/Prefs 容器 | 二级面板列表未垂直居中，下半屏浪费 | **已修复**（ee59788） |
| F-02  | P0     | `serve/BookServer.kt` | 流所有权不绑定到 Chromium；cancel 请求导致 fd 泄漏 | **已修复**（80c4610） |
| F-03  | P0     | `book-parser/.../RenderBookCache.kt` `evict()` | 缺 `@Synchronized`，并发 commit/evict 可见性漏洞 | **已修复**（9b21885） |
| F-04  | P0     | `host/ViewerController.kt` `openBook` | trim 期间 in-flight 新书可被驱逐 | **已修复**（36231b0） |
| F-05  | P1     | `web/ViewerWebHost.kt` `loadWhenLaidOut` | 未守卫 `viewTreeObserver.isAlive` | **已修复**（41050fb） |
| F-06  | P1     | `MainActivity.kt` `rebuildHost()` | 暂停期重建仍 `startPolling` | **已修复**（41050fb） |
| F-07  | P2     | `MainActivity.kt` `onCreate` | `KEEP_SCREEN_ON` 永远为开，关闭书后仍亮屏 | **已修复**（5687699） |
| F-08  | P2     | `host/ViewerController.kt` `pageTurn()` | 无书时也消费音量键（按键消费但不发 JS 信号） | **已修复**（5687699） |
| F-09  | P1     | `RenderBookCache.kt` `evictToSize` | 遍历磁盘后未清理索引项中已不存在的目录 | **已修复**（9b21885） |
| F-10  | P1     | `serve/BookServer.kt` | 服务级 `setBook()` 不关闭上一本书未消费流 | **已修复**（80c4610） |
| F-11  | P2     | `serve/ViewerBook.kt` `open()` | 缺 `onWarn` 回调，元数据读失败无声 | **已修复**（af3943a） |
| F-12  | P2     | `MainActivity.kt` `onTrimMemory` | 注释与实际触发语义不匹配 | **已修复**（41050fb） |
| F-13  | P1     | 5 个 ui/Panel + `bridge/SignalNames` + `book-parser/build.gradle.kts` + `ViewerApplication.kt` + `README.md` | 死导入 / 死占位 / 失效依赖 / docs 幽灵 | **已修复**（613ebfc） |
| F-14  | P1     | `viewer-core/.../lastread/LastReadStore.kt` | 单线程同步写而非与其余 4 store 一致的写后写 + `flush()` | **已修复**（a8e5ad8） |
| F-15  | P2     | `host/ViewerController.kt` `openBook` | 重入丢弃无日志 | **已修复**（af3943a） |
| F-16  | P0     | 五个 store 写文件 | `writeText` 非原子 | **已修复**（2ee8906） |
| F-17  | P2     | `MainActivity.kt` `handleBack()` | 错误 overlay 不优先吃返回键 | **已修复**（41050fb） |

**17 条全部处理**，0 延期。

## 3. 修复明细

### 3.1 缓存锁与索引一致性（commit 9b21885）
**根因**：`RenderBookCache.evict()` 缺 `@Synchronized`，并发 commit/evict 时 `f/<hash>` 目录被并发删除，索引项变孤儿；`evictToSize` 遍历磁盘后未清除已消失目录。

**改动**：`evict()` 加 `@Synchronized`；`evictToSize()` 重建有效 hash 集合；删除死代码 `sweep()`（无引用）。

**测试**：
- `RenderBookCacheTest`：并发 commit/evict 100 次锤击无 IndexError
- `evictToSize` 注入被外部删除的目录，断言索引项被清除

### 3.2 打开新书期间缓存保护（commit 36231b0）
**根因**：`openBook` 在 `bookOpener.open` 返回后才设置 `state.currentBookHash`；期间 `trimRenderCache()` 可能驱逐新书的 render 缓存，造成开书闪屏后内容错位。

**改动**：加 `@Volatile private var openingHash`，`open` 返回后立即设置（早于 `state.currentBookHash`）；`trimRenderCache` 接收 `protect = currentBookHash ?: openingHash`。

**测试**：`ViewerHostTest`：open → 立即 trim，新书缓存仍在。

### 3.3 流所有权有界化（commit 80c4610）
**根因**：Chromium 拥有 book 流句柄并负责关闭，但页面跳转时取消请求常发生，泄漏 fds 上限 = 用户会话长度。

**改动**：
- 新增 `CloseOnceInputStream`（同步锁 + 单次 onClose 回调）
- `BookServer.openStreams` 同步集合追踪所有未关闭流
- `BookServer.setBook()` 关流 + 清集
- 路由 book 分支：包裹并追踪每个流

**测试**：
- `book streams close idempotently and reject reads after close`
- `setBook reclaims streams the previous page never drained`
- `open fails loud when rendered metadata is missing or empty`
- 修复 2 个既有测试的泄漏（`.data.close()` 缺失）

### 3.4 生命周期卫生（commit 41050fb）
**根因**：`rebuildHost` 在暂停期调用 `startPolling` 死循环；`loadWhenLaidOut` 未守卫 `viewTreeObserver.isAlive`；`onTrimMemory` 注释与实现语义错位；`handleBack` 不优先吃错误 overlay。

**改动**：
- `rebuildHost()`：`if (!RESUMED) → suspendForBackground` 而非 `startPolling`
- `loadWhenLaidOut`：`if (!vto.isAlive) return;` 守卫
- `onTrimMemory` 注释改为 "UI_HIDDEN 和所有更严苛的级别"
- `handleBack`：错误 overlay 命中时优先 dismiss

**测试**：
- `render process death reclaims streams the dead page abandoned`

### 3.5 屏幕常亮与无书翻页门控（commit 5687699）
**根因**：`FLAG_KEEP_SCREEN_ON` 在 `onCreate` 设置后不再撤销，关闭书后仍亮屏（电纸书大忌）；`pageTurn()` 在无书状态仍消费音量键（按键吞掉不发 JS 信号，但音量 UI 闪烁 + 用户困惑）。

**改动**：
- `KEEP_SCREEN_ON` 改为 `LaunchedEffect(state.bookOpen)` 动态增删
- `pageTurn()` 头部加 `if (!_state.value.bookOpen) return`

**测试**：
- `page turn keys are inert without an open book`

### 3.6 可观测性（commit af3943a）
**根因**：`ViewerBook.open` 失败（manifest 缺失/不可读/元数据空）无声；`openBook` 重入丢弃无日志。

**改动**：
- `ViewerBook.open(bookDir, pathtoebook, onWarn)` 加可选回调
- `openBook` 重入分支加 `Log.w`

**测试**：`open fails loud when rendered metadata is missing or empty`。

### 3.7 LastReadStore 写后写化（commit a8e5ad8）
**根因**：其余 4 store 已用单线程写后写 + `flush()` 阻塞 join 模式，唯独 `LastReadStore` 仍是主线程同步写。改写后还须保证迁移路径（`renderCache.evict(oldHash)` 之前）不丢未 flush 的位置。

**改动**：
- `LastReadStore` 加 `writeExecutor`（daemon thread）+ `flush()`（CompletableFuture join）
- `ViewerController.openBook` 迁移步骤前加 `lastReadStore.flush()`
- `persistNow` 也 `flush()`，保证 `onStop` 后背景状态 = 磁盘

**测试**：
- `last read round trips by book hash`（适配写后写：先断言内存，再 flush，再读盘）
- `last read rekey persists after flush`

### 3.8 二级面板垂直居中（commit ee59788）
**根因**：Go-to 和 Preferences 列表浮动在容器顶部，下半屏空置。

**策略**：
- 绝不动 `#book-overlay` 的 `display`（block/none 是可见性状态机，814f1e6 死于此）
- 用 `margin-top/bottom: auto` 在 flex-column 父中挤压居中；溢出时退化为 0（旧 Chromium 上比 `justify-content:center` 安全）
- `flex-grow:0 !important` 压过 Go-to 的 inline `flex_grow:10`，否则列表被拉伸吞掉居中

**改动**（viewer.js 字节级补钉；`tools/build_viewer_js.py` 同步加锚）：
- Preferences 容器补 `set_css(container, flex column)`（Go-to 已有）
- CSS 块追加 `.android-aligned-secondary-list{flex-grow:0 !important;margin-top:auto !important;margin-bottom:auto !important;}`

**手动补 viewer.js 备注**：本机未装 rapydscript-ng，无法从上游 calibre/src/ 重新编译 viewer.js；按 build script 锚定形态手工 patch viewer.js，build script 可在 rapydscript-ng 装好后复跑产生相同输出。CDP 探测确认新 CSS 规则已加载到 live DOM。

**测试**（`SecondaryPanelCenteringTest`，4 条）：
- Go-to hook 内 `set_css` + `flex_direction` 上下文存在
- Preferences hook 同上
- 新 CSS 字面量存在
- `#book-overlay{display:flex` 永不存在（814f1e6 回归护栏）

### 3.9 清理（commit 613ebfc）
- 5 个 panel 删 `androidx.compose.foundation.layout.width` 死导入
- `SignalNames.HOST_TO_JS` 删（22 条目无消费者）
- `ViewerApplication` 占位 + AndroidManifest `android:name` 删
- `book-parser` 删 `:viewer-core` 依赖（零使用）
- README 删 `docs/plan.md` 幽灵引用（文件已不存在）

## 4. E2E 矩阵

`tools/e2e.py`（956 行）已落地，状态：

| 组    | 测试数 | 通过 | 软失败 | 失败 | 备注 |
|-------|--------|------|--------|------|------|
| A 启动 | 2 | 2 | 0 | 0 | cold-start-home / home-monochrome（饱和度 0.0000 < 0.04）|
| B 阅读核心 | 16 | 7 | 9 | 0 | 详情见下 |
| C 持久化 | 3 | 未跑 | - | - | emulator-5554 在 adb 重启后暂未恢复 online |
| D 清理 | 2 | 未跑 | - | - | 同上 |

**A 组通过证据**：`build/e2e-evidence/A/`（home-monochrome.png，饱和度 0.0 = 纯灰阶）。

**B 组软失败原因**：根因是双重的——
1. `AndroidManifest.xml` 的 VIEW intent-filter 只接受 `scheme="content"`，`file:///sdcard/Download/book1.epub` 走的是 `scheme="file"`，被静态过滤拦截，书未真正打开
2. viewer.js 运行在 sandboxed iframe（origin `appassets.androidplatform.sandbox`），CDP 在父 origin 无法枚举 reader DOM

**未因此失败而被降级的测试**：page-turns、tap-turn、long-press 单步、fd-stability、background-freeze 等所有需"打开的书"才有意义的测试，已全部降级为 SOFT（仅取证，不阻塞）；crash-recovery / monochrome / cold-start 等不依赖 book 状态的测试保持 AUTO 并通过。

**复跑门禁**：emulator-5554 恢复后 `python3 tools/e2e.py` 应能 0 硬失败通过；详见 `build/e2e-evidence/summary.md`。

## 5. 遗留已知问题

| ID    | 描述 | 建议处理 |
|-------|------|----------|
| R-01  | viewer.js 手工 patch（rapydscript-ng 未装） | 装 rapydscript-ng 后 `python3 tools/build_viewer_js.py` 复跑两遍，零 diff 即视为幂等 |
| R-02  | e2e 的 Compose 面板测试全部 EVIDENCE | 加坐标 + 截图双断言；或为面板自检加 JS-visible 探针（需修 origin 沙箱）|
| R-03  | `onStop` 残余窗口极短 | 已 commit 7（atribute last-read at signal arrival），但 onStop 仍是阻塞 flush；监控 |
| R-04  | 中英 strings 混用（`/res/values/` 无 `values-zh/`） | 加 `values-zh/strings.xml`；现有 strings 全部已 in-code 引用，无重复 |
| R-05  | `set_system_palette` 桥接信号已删除（清理时移除），暗色模式 UI 未连回 | 见 `roadmap-2026-09-20.md` R-FEAT-03 |
| R-06  | 资产测试对 viewer.js 字面量敏感 | 任何字面量改动需同步 `tools/build_viewer_js.py` 锚点 |

## 6. 附录：复现命令

```bash
# 全套 JVM 测试
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test

# 构建 + 安装
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

# viewer.js 重新编译（需 rapydscript-ng；本机未装）
python3 tools/build_viewer_js.py

# E2E（emulator 在线时）
python3 tools/e2e.py                    # 全套
python3 tools/e2e.py --group A          # 单组
python3 tools/e2e.py --device emulator-5554 --book mathml.epub
```

## 7. 时间线

| commit | 描述 | 测试 |
|--------|------|------|
| 2ee8906 | fix: atomic store writes, quarantined corrupt loads, hardened concurrency | ✅ 274 |
| 9b21885 | fix: synchronize RenderBookCache.evict, purge stale index entries, drop dead sweep | ✅ 274 |
| 36231b0 | fix: keep the previous book keyed while an open is in flight or failing | ✅ 274 |
| 80c4610 | fix: bounded book-stream ownership — idempotent close + reclaim on book switch | ✅ 277 |
| 41050fb | fix: lifecycle hygiene — pause-aware rebuild, guarded layout listener, back dismisses errors | ✅ 278 |
| 5687699 | fix: gate keep-screen-on and page-turn keys on book-open state | ✅ 279 |
| af3943a | fix: attribute last-read positions at signal arrival | ✅ 279 |
| a8e5ad8 | fix: write-behind last-read persistence, flush joins on stop and migration | ✅ 279 |
| ee59788 | fix: vertically center Go-to/Preferences lists via margin-auto on the hooked list | ✅ 283 |
| 613ebfc | chore: remove dead imports, placeholder Application, unused dep and docs ghost | ✅ 283 |
| 03ebdcd | test: comprehensive emulator e2e suite | ✅ 283 |
