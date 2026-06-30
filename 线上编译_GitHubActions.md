# 用 GitHub Actions 在线生成 APK

## 步骤

1. 注册或登录 GitHub。
2. 新建一个仓库，例如 `TabletSubtitleTranslator`。
3. 把本工程所有文件上传到仓库根目录，确保能看到：
   - `app/`
   - `build.gradle`
   - `settings.gradle`
   - `.github/workflows/build-apk.yml`
4. 打开仓库的 `Actions` 页面。
5. 选择左侧 `Build Android APK`。
6. 点击 `Run workflow`。
7. 等待构建完成。
8. 在构建结果页面底部下载 `TabletSubtitleTranslator-debug-apk`。
9. 解压后得到 `app-debug.apk`，传到平板安装。

## 说明

这是 debug APK，可以直接安装测试。首次安装时平板可能提示“未知来源应用”，需要允许安装。
