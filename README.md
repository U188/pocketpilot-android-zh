# PocketPilot 简体中文版

PocketPilot 是一个 Android 客户端，可通过 SSH 连接自己的服务器，并在手机上使用 OpenCode、SFTP 文件管理器、代码编辑器和终端。本项目基于上游 [renderr03/pocketpilot-android](https://github.com/renderr03/pocketpilot-android)，增加简体中文翻译和上游同步构建流程。

> 本项目是非官方分支，与 OpenCode 团队没有关联，也未获其认可。

## 项目状态

- 上游：[`renderr03/pocketpilot-android`](https://github.com/renderr03/pocketpilot-android)
- 默认分支：`main`
    - 汉化状态：711 条 Android 字符串资源均已覆盖，工作区、聊天、文件、终端、设置、Git、用量、模型、安装向导和系统提示已翻译为简体中文。
    - 俄文输入兼容：提示词命令名仍允许俄文字母，方便用户自行输入俄文斜杠命令；这不影响界面语言。
    - 构建状态：GitHub Actions 先运行单元测试，通过后构建中文 Debug APK 并发布到本仓库 GitHub Releases。

## 上游更新时会发生什么

`.github/workflows/sync-upstream.yml` 每天 UTC 03:17（北京时间 11:17）检查上游 `main`，也可以在本仓库的 **Actions → Sync upstream and build Chinese APK → Run workflow** 手动触发。

1. 工作流抓取上游 `main`，并将上游提交合并到本仓库 `main`。
2. 如果合并后的分支相对本仓库原分支有变化，就把合并结果推送到本仓库 `main`。如果上游没有新提交，不会产生新的推送。
3. 每次成功运行都会构建中文 Debug APK、上传 Actions 工件，并发布到本仓库 Releases；版本号使用 `v1.0.<GitHub Actions 运行编号>`。
4. 应用启动时按“自动检查更新”设置检查本仓库 Releases。发布的 APK 使用仓库固定签名密钥，后续版本可在同一安装上升级。
5. 如果上游改动与本地修改冲突，合并步骤会失败并停止构建与发布；工作流不会自动覆盖冲突文件。需要先人工解决冲突，再重新运行。
6. 上游提交会带入上游自己的 README、工作流和其他文件。README 若被上游更新覆盖，需要重新应用本项目的中文说明；资源文件若发生冲突，也需要检查并保留汉化。

## 安装

在本仓库 [**Releases**](https://github.com/U188/pocketpilot-android-zh/releases) 下载最新的 `app-debug.apk`。应用内更新也会检查这个汉化仓库。

早期 `v1.0.1` 是汉化资源加入前发布的上游初始 APK，且使用了不同签名。由它迁移到固定签名版本时，需要先卸载旧版再安装；卸载会清除应用本地数据。此后的版本会使用固定签名，可直接升级。

## 本地构建

项目使用上游 Gradle 配置。安装 JDK 17、Android SDK，并准备 Gradle 9.3.1 后，在项目目录运行：

```bash
gradle assembleDebug
```

上游仓库没有提交 Gradle Wrapper 脚本。本地构建需准备 JDK 17、Android SDK 和 Gradle 9.3.1；持续集成会从固定仓库密钥恢复 Debug 签名证书。

## 参与汉化

- Android 字符串资源位于 `app/src/main/res/values-zh-rCN/strings.xml`。
- 与上游同名资源保持一致，保留 `%1$s`、`%1$d` 等格式占位符。
- Compose 页面里直接写入的界面文本也需要逐步提取并翻译。
- 提交前可通过 Actions 手动运行同步与构建，确认资源编译通过。

## 许可

本项目遵循上游 [MIT 许可证](LICENSE)。
