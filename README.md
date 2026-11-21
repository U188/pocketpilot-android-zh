# PocketPilot 简体中文版

PocketPilot 是一个 Android 客户端，可通过 SSH 连接自己的服务器，并在手机上使用 OpenCode、SFTP 文件管理器、代码编辑器和终端。本项目基于上游 [renderr03/pocketpilot-android](https://github.com/renderr03/pocketpilot-android)，增加简体中文翻译和上游同步构建流程。

> 本项目是非官方分支，与 OpenCode 团队没有关联，也未获其认可。

## 项目状态

- 上游：[`renderr03/pocketpilot-android`](https://github.com/renderr03/pocketpilot-android)
- 默认分支：`main`
- 汉化状态：进行中。目前覆盖一部分 Android 字符串资源；上游 711 条字符串中已有 170 条简体中文翻译，部分界面仍会显示英文，Compose 中直接编写的文本也尚未全部汉化。
- 构建状态：GitHub Actions 可构建中文 Debug APK，并作为 Actions 工件提供下载。

## 上游更新时会发生什么

`.github/workflows/sync-upstream.yml` 每天 UTC 03:17（北京时间 11:17）检查上游 `main`，也可以在本仓库的 **Actions → Sync upstream and build Chinese APK → Run workflow** 手动触发。

1. 工作流抓取上游 `main`，并将上游提交合并到本仓库 `main`。
2. 如果合并后的分支相对本仓库原分支有变化，就把合并结果推送到本仓库 `main`。如果上游没有新提交，不会产生新的推送。
3. 无论是否有上游新提交，工作流都会尝试构建 Debug APK，并上传为 Actions 工件，保留 30 天。
4. 目前不会自动创建 GitHub Release，也不会发布长期下载链接。下载入口在本仓库的 **Actions → 对应运行记录 → Artifacts**。
5. 如果上游改动与本地修改冲突，合并步骤会失败并停止本次构建；工作流不会自动覆盖冲突文件。需要先人工解决冲突，再重新运行。
6. 上游提交会带入上游自己的 README、工作流和其他文件。README 若被上游更新覆盖，需要重新应用本项目的中文说明；资源文件若发生冲突，也需要检查并保留汉化。

## 安装

在本仓库 **Actions** 中打开成功的 `Sync upstream and build Chinese APK` 运行记录，在页面底部的 **Artifacts** 下载 APK。该构建使用临时 Debug 签名密钥，不能保证覆盖安装此前使用其他密钥签名的版本。

当前不会自动发布 Release。若需要稳定的版本下载页或应用内升级，需另外配置正式签名和 Release 发布流程。

## 本地构建

项目使用上游 Gradle 配置。安装 JDK 17、Android SDK，并准备 Gradle 9.3.1 后，在项目目录运行：

```bash
gradle assembleDebug
```

上游仓库没有提交 Gradle Wrapper 脚本。Debug 构建需要 `debug.keystore`；可按需使用 Android 默认 Debug 密钥，或自行生成一个本地密钥。

## 参与汉化

- Android 字符串资源位于 `app/src/main/res/values-zh-rCN/strings.xml`。
- 与上游同名资源保持一致，保留 `%1$s`、`%1$d` 等格式占位符。
- Compose 页面里直接写入的界面文本也需要逐步提取并翻译。
- 提交前可通过 Actions 手动运行同步与构建，确认资源编译通过。

## 许可

本项目遵循上游 [MIT 许可证](LICENSE)。
