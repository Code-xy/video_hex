# HtvApp — 网站转安卓 App（WebView + GSY 原生播放器）

把 `hsex.tv` 包成原生 APK。**不复刻网站**，只做壳 + 播放器桥接。

## 实测到的站点结构（2026-10 侦察）

| 项 | 值 |
|---|---|
| 详情页 | `/video-<id>.htm` |
| 视频地址 | `<source id="video-source" src="https://cdn.hdcdn.store/<ts>/<token>/hls/<id>/index.m3u8" type="application/x-mpegURL">` |
| 协议 | 标准 HLS（ExoPlayer 原生支持） |
| 4 条线路 | `cdn.hdcdn.store` / `shark.hdcdn.store` / `fdc.hdcdn.store` / `shark-v6.hdcdn.store` |
| 播放器 | video.js 8.22.0 |
| 统计上报 | `stats.91.lol/stats/video` |
| 广告域 | `go.tscprts.com`、`go.whitetrafsa.com`（已拦截） |

地址明写在 HTML 里，**不需要逆向任何加密**。token 带时间戳会过期 → 每次都从当前页面实时取，不缓存。

## 架构

```
MainActivity (WebView 壳)
  ├─ WebViewCompat.addDocumentStartJavaScript  → assets/inject.js
  ├─ shouldInterceptRequest                    → 拦截广告域
  ├─ JS Bridge "Seagull"                       → play/toast/log
  ├─ 探测 4 条 CDN 线路（HttpURLConnection + Range）→ 选第一个能通的
  └─ Intent → VideoActivity
                  └─ StandardGSYVideoPlayer（带 Cookie/Referer/UA）
                        └─ 退出回传播放位置 → __sgResume() 网页续播
```

**关于请求头（实测结论）**：CDN **没有防盗链**，裸拉 m3u8 直接 `200 + #EXTM3U`。
App 里仍然带 `Referer`/`Cookie`/`UA`，是为了站点以后加保护时不用改代码——不是当前必需项。

### 播放链路实测数据

| 项 | 结果 |
|---|---|
| m3u8 无头直拉 | `200` + `#EXTM3U` |
| `cdn.hdcdn.store` | `200` ✅ |
| `shark.hdcdn.store` | `200` ✅ |
| `fdc.hdcdn.store` | `200` ✅ |
| `shark-v6.hdcdn.store` | `SSL EOF` ❌（纯 IPv6 线路，IPv4-only 网络下必然失败） |
| TS 分片 | `206` + `video/mp2t`，真流 |
| 播放列表 | `EXT-X-VERSION:3` / `MEDIA-SEQUENCE:0` → **VOD 点播**（可拖动） |
| token | 每次请求都变 → **必须实时读页面，禁止缓存 URL** |

## 用法

1. 改起始地址：`app/src/main/res/values/strings.xml` 里的 `start_url`
2. 推 GitHub：
```bash
git init && git add . && git commit -m "feat: webview + gsy player"
git branch -M main && git remote add origin <你的仓库> && git push -u origin main
```
3. 仓库 **Actions → Build APK → Run workflow**，跑完在底部 **Artifacts** 下 APK
4. 想给别人固定链接：`git tag v1.0.0 && git push origin v1.0.0` → 自动发 Release

## 编译链（实测固定）

| 组件 | 版本 | 备注 |
|---|---|---|
| AGP | 8.11.1 | 需要 ≥8.10 才能 compileSdk 36 |
| Gradle | 8.13 | CI 里用 setup-gradle 装，**不用** wrapper（缺 jar） |
| Kotlin | 2.2.0 | |
| compileSdk / targetSdk / minSdk | 36 / 35 / 23 | |
| GSYVideoPlayer | 14.0.0 (JitPack) | 传递 `gsyvideoplayer-exo2` → **androidx.media3 1.10.1** |

`androidx.media3 1.10.1` 是强制 `compileSdk 36` 的根因，改不动就别动 compileSdk。

## 功能

- 原生播放器全屏播放，比 WebView 内置 `<video>` 稳
- 自动探测 4 条线路，主站挂了自动切
- **长按标题栏 = 手动换线路**
- 退出播放器自动回网页续播
- 拦截弹窗广告域
- 支持文件上传（WebView `onShowFileChooser`）
- 视频下载（带 Cookie/Referer）

## 已知限制

- 只能侧载/自签分发，**上架 Google Play 会因成人内容被拒**
- 不配 keystore secret 就用 debug 签名，能装，但不能覆盖安装正式签名的版本
- 站点若改版换 CDN 域名，改 `MainActivity.kt` 里的 `cdnHosts` 即可
- DRM / 加密私有协议不支持（这站没有，是裸 HLS）

---

# 方案 B（当前版本）：原生列表 + WebView 兜底

已从 A（纯 WebView 壳）升级到 B。浏览和播放都是原生，WebView 只在必要时兜底。

## 页面结构（全部实测，非推测）

| 用途 | URL 模板 | 卡片/页 |
|---|---|---|
| 推荐 | `index-N.htm` | 48 |
| 最新/全部 | `list-N.htm` | 24（深至 29239 页） |
| 排行 | `top_list-N.htm` | 24 |
| 7日榜 | `top7_list-N.htm` | 24 |
| 5分钟 | `5min_list-N.htm` | 24 |
| 长片 | `long_list-N.htm` | 24 |
| 搜索 | `search.htm?search=X&sort=new` | 20 |
| ~~热门~~ | `hot_list-N.htm` | **404，站点自己挂了** |

## 卡片 DOM（Jsoup 选择器依据）

```html
<div class="thumbnail">
  <a href="video-<id>.htm">
    <div class="image"
         style="background-image: url('https://i.hdcdn.store/thumb/<id>.webp')"
         title="完整标题">
      <div class="marker-overlays">
        <var class="duration">28:24</var><span class="hd-thumbnail">HD</span>
      </div>
    </div>
  </a>
  <div class="caption title"><h5><a href="video-<id>.htm">标题</a></h5></div>
  <div class="info"><p><a href="user.htm?author=xxx">作者</a><br/></p></div>
</div>
```

## 解析器实测通过率

10 个页面 × 全部字段 **零缺失**：标题 100%、缩略图 100%、时长 100%、作者 100%、HD 标记正常。

## 架构

```
MainActivity（原生列表，方案 B 主入口）
  ├─ OkHttp + Jsoup 解析 index-/list-/top_/top7_/5min_/long_/search
  ├─ RecyclerView GridLayoutManager(2) + Coil 图片（实测 thumb 无需 Referer）
  ├─ 滚动到底自动翻页；下拉刷新
  ├─ 底栏：推荐 / 最新 / 排行 / 搜索 / 网页
  ├─ 点击卡片 → 抓详情页 m3u8 → 直接进 GSY（不用过 WebView）
  └─ 长按卡片 / 解析失败 → WebActivity 兜底
WebActivity（WebView 兜底：登录态 / 异常页 / 主动要网页）
VideoActivity（GSY 全屏，长按标题换线路，退出回传位置续播）
```

## 关键实测结论

- `search.htm?...&sort=new` 的 **`&page=` 参数无效**，只有第一页 20 条 → 代码里搜索完直接置 `reachedEnd`
- 缩略图 `i.hdcdn.store/thumb/<id>.webp` **无需 Referer**，Coil 直连 200 `image/webp`
- m3u8 与 TS 分片**均无防盗链**，裸拉 200 / 206
- m3u8 token **每次请求都变** → 必须实时抓详情页，禁止缓存
- `shark-v6.hdcdn.store` 是纯 IPv6 线路，IPv4 网络下必失败 → 探活时自动跳过
- HTML 属性选择器**必须带引号**（`a[href*='video-']`），否则严格 CSS 解析器报错
