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
