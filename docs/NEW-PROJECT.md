# 新安卓项目 —— 从零到出包

## 第一步：建空仓库（只能你来做）

沙箱里没有 GitHub token，接口建仓库会返回 `401 Requires authentication`；
SSH key 只能推已存在的仓库，**无法创建仓库**。所以这一步必须你点一下：

**一键预填链接**（把 `名字` 换掉即可）：

```
https://github.com/new?name=名字&visibility=public&description=安卓云端构建项目
```

只要**名字**，**不要**勾 "Add a README"、不要加 .gitignore、不要选 license ——
保持**完全空仓库**，我这边首推才不会冲突。

> 想让我全自动（建仓库 + 推送 + 触发构建）的话，需要一个 Fine-grained token，
> 权限给 `Administration: Read and write` + `Contents: Read and write`。非必需。

## 第二步：我这边做的（有仓库地址即可）

1. 本地建工程 → `git init` → remote 指向你的仓库
2. `.github/workflows/build.yml` 加三行，引用可复用模板：

```yaml
name: Build APK
on:
  push: { branches: [ main ] }
  workflow_dispatch:
permissions:
  contents: write
jobs:
  build:
    uses: Code-xy/video_hex/.github/workflows/android-build.yml@main
    secrets: inherit
```

3. 首推 → CI 出包 → 从固定链接拿 APK：

```
https://github.com/<你>/<新仓库>/raw/ci-logs/.ci/app-release.apk
https://github.com/<你>/<新仓库>/raw/ci-logs/.ci/build.log
```

> 注意：复用模板会往**你新仓库**的 `ci-logs` 分支推日志和 APK，
> 所以新仓库的 Actions 需要 `contents: write`（上面已给）。

## 第三步：签名（第一版就要定下来）

**这一步不能拖到以后做。** 如果第一版用 debug 签名、后面换成正式 keystore，
用户（包括你自己）必须卸载重装，不能覆盖升级。

两种方式：

| 方式 | 做法 | 适用 |
|---|---|---|
| **A 仓库内置**（简单） | 沙箱用 `keytool` 生成 `signing/dev-release.jks` 提交进仓库，`.gitignore` 加 `!signing/dev-release.jks` 白名单 | 侧载自用 |
| **B Secrets**（保密） | 加 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 四个 secret | 要正经发布 |

`app/build.gradle.kts` 的签名优先级已固定为：**secrets 环境变量 > 仓库内置 jks > debug**，
所以先用 A 起步，将来换 B 不用改代码。

⚠️ 验证签名是否稳定，**要比对证书 SHA256 指纹，不要比对 `META-INF/CERT.RSA` 字节** ——
PKCS#7 带 signingTime 时间戳，每次构建字节都不同，但 Android 只认证书。

```bash
# 正确判据
keytool -printcert -file <(unzip -p app.apk META-INF/CERT.RSA) | grep SHA256
```

## 编译前先跑的自检（能挡掉大部分低级错误）

```bash
python3 /workspace/check.py <源码目录>              # 括号平衡（先剥字符串再剥注释）
python3 /workspace/check_workflow.py <workflow.yml> # 抓 if: 里误用 secrets
python3 /workspace/axml.py <apk>                    # 读 APK 二进制清单验包名/版本/权限/组件
```

## 已知坑速查

| 症状 | 原因 |
|---|---|
| run 名退化成文件路径、0 秒失败、零步骤记录 | workflow 里 `if:` 引用了 `secrets` → 整个工作流非法 |
| 读 CI 日志 403 | 未认证 API 限流 60/h → 改读 `ci-logs` 分支 |
| 烂包也算构建成功 | 少了 `set -o pipefail` + `tee` |
| APK 里多出几十 MB 的 `.so` | 传递依赖塞进来的播放器内核，用 `packaging.jniLibs.excludes` 剔 |
| 覆盖安装报签名不一致 | 签名没固定；比证书指纹确认 |
