# 拂月译屏 · MiniMax 版

> 一个用于手机和平板观看外语视频时的悬浮字幕 OCR 翻译与学习工具。  
> 适合看美剧、日剧、课程视频、访谈、纪录片等场景。

## 特别说明：本项目的开发方式

这个项目从需求讨论、交互设计、Android 源码生成、Bug 修复、功能迭代到 README 文档整理，**全程都是通过 ChatGPT 5.5 Thinking 辅助完成的**。

项目所有代码均由 ChatGPT 5.5 Thinking 根据需求逐步生成和修改。

我本人没有手写任何一行 Android 代码，也没有使用 Android Studio 本地开发。

APK 的构建也不是在本地完成的，而是通过 **GitHub Actions 在线编译** 自动生成调试版 APK。

也就是说，这个项目的完整开发流程是：

```text
提出需求
→ ChatGPT 5.5 Thinking 生成 / 修改源码
→ 上传到 GitHub
→ GitHub Actions 在线编译 APK
→ 下载 APK 安装测试
→ 继续反馈问题
→ ChatGPT 5.5 Thinking 继续迭代
```

这个仓库也可以看作是一次“用 AI 从零生成并持续迭代 Android 应用”的完整实践记录。

## 项目简介

拂月译屏是一款 Android 悬浮字幕翻译器。

它通过系统屏幕录制权限获取当前画面，在视频播放时后台缓存最近约 2 秒的完整屏幕帧。当用户点击悬浮按钮暂停视频后，可以对当前字幕或缓存帧进行 OCR 识别，并调用 MiniMax 模型进行翻译或生成学习卡片。

核心目标是：

```text
看外语视频时，不打断观看体验
快速暂停
识别字幕
翻译成中文
提取重点词和语法
收藏学习内容和截图
```

## 主要功能

### 1. 悬浮字幕翻译

启动后会显示一个名为 `幕` 的悬浮按钮。

点击 `幕`：

```text
展开菜单
尝试暂停视频播放
```

再次点击 `幕`：

```text
收起菜单
尝试恢复视频播放
```

菜单按钮包括：

```text
译：识别字幕并调用 MiniMax 翻译成中文
学：识别字幕并调用 MiniMax 生成学习卡片
藏：收藏当前学习卡片和完整截图
区：调整 OCR 字幕识别区域
关：关闭悬浮窗
```

### 2. MiniMax 翻译

`译` 按钮会：

```text
OCR 识别字幕原文
调用 MiniMax
只输出自然、精简的中文译文
```

显示结果时会先显示原文，再显示译文：

```text
原文：
<识别到的字幕>

中文译文：
<MiniMax 翻译结果>
```

### 3. MiniMax 学习卡片

`学` 按钮会：

```text
OCR 识别字幕原文
调用 MiniMax
生成中文翻译、重点词、语法说明
```

显示结构：

```text
原文
中文
重点词
语法
```

学习卡片适合用来积累外语表达、单词和句型。

### 4. 回溯缓存帧

应用在菜单未展开时，会后台预缓存最近的完整屏幕帧：

```text
每 0.25 秒缓存 1 帧
每秒约 4 帧
最多保留 8 帧
覆盖最近约 2 秒
```

长按 `译` 或 `学`：

```text
出现缓存回放预览窗口
左滑选择更早的缓存帧
松手后识别当前选中的缓存帧
```

这可以解决字幕一闪而过、点击暂停时字幕已经消失的问题。

### 5. 缓存预览窗口

缓存预览窗口支持：

```text
完整缓存帧显示
手机和平板自适应布局
不透明背景
长按期间显示
松手后立即消失
```

平板和手机使用不同窗口策略：

```text
平板：窗口更大，适合大屏观看
手机：窗口更保守，避免超出屏幕
```

### 6. OCR 区域调整

点击 `区` 后可以调整字幕识别窗口：

```text
+：增大 OCR 区域高度
-：减小 OCR 区域高度
上：字幕窗口上移
下：字幕窗口下移
```

调整 OCR 区域不会清空缓存，因为缓存保存的是完整屏幕帧，OCR 时才按当前区域裁剪识别。

### 7. 手机端增强 OCR

为了适配手机端视频画面比例、字幕位置和播放器 UI 差异，回溯识别时会在同一张缓存帧内尝试多个区域：

```text
当前 OCR 窗口
多个字幕高度
多个离底位置
整帧 OCR 兜底
```

严格保证：

```text
不会切换到其他时间的缓存帧
结果仍来自当前预览帧
```

### 8. 收藏功能

点击 `藏` 会收藏当前学习卡片。

收藏内容包括：

```text
识别原文
MiniMax 学习卡片
完整截图
JSON 原始数据
收藏时间
```

收藏截图现在统一保存完整识别来源帧：

```text
手机端：保存完整屏幕帧
平板端：保存完整屏幕帧
短按学：保存完整当前屏幕帧
长按回溯学：保存完整缓存帧
```

OCR 仍然可以只裁剪字幕区域识别，但收藏截图不会只保存 OCR 小窗口。

### 9. 我的收藏

App 首页提供“我的收藏”入口。

收藏页支持：

```text
查看收藏列表
显示截图缩略图
查看原文标题
预览完整截图
删除选中收藏
清空全部收藏
```

## MiniMax 设置

App 首页可以填写：

```text
MiniMax API Key
模型名
接口地址
```

默认配置：

```text
模型：MiniMax-M3
接口：https://api.minimaxi.com/v1/text/chatcompletion_v2
```

API Key 保存在 Android App 私有存储中，不写入源码。

## 权限说明

应用需要以下权限或系统授权：

### 1. 悬浮窗权限

用于显示 `幕` 悬浮按钮和菜单。

如果跳转系统设置开启权限后返回 App，应用会自动重新检查权限，不需要重启。

### 2. 屏幕录制权限

用于截图当前视频画面并进行 OCR。

这是 Android 系统弹窗授权，每次启动悬浮翻译器时可能需要确认。

### 3. 通知权限 / 前台服务

用于保持悬浮翻译器服务运行。

Android 13 及以上系统可能需要单独授权通知权限。

## 在线编译方式

本项目不需要本地安装 Android Studio。

APK 通过 GitHub Actions 在线编译。

流程：

```text
把源码上传到 GitHub
进入 Actions
运行 Build Android APK workflow
等待编译完成
下载生成的 APK artifact
安装到 Android 手机或平板
```

典型 Git 命令：

```bash
git add .
git commit -m "Update subtitle translator app"
git push
```

如果需要把多次提交合并成一笔提交并强制上传，可以使用：

```bash
BRANCH=$(git branch --show-current)

git branch backup-before-squash

git switch --orphan one-commit-version

git add -A
git commit -m "Initial complete subtitle translator app"

git branch -D "$BRANCH"
git branch -m "$BRANCH"

git push --force-with-lease origin "$BRANCH"
```

注意：这会重写 GitHub 远程提交历史。

## GitHub Actions 编译配置

项目内置 GitHub Actions workflow，会自动：

```text
检出源码
安装 JDK 17
安装 Android SDK
安装 Gradle
执行 assembleDebug
上传 APK artifact
```

生成的 APK 通常位于：

```text
app/build/outputs/apk/debug/*.apk
```

## 使用方法

1. 打开 App。
2. 填写并保存 MiniMax API Key。
3. 授权悬浮窗权限。
4. 授权通知权限。
5. 点击“启动悬浮翻译器”。
6. 授权屏幕录制。
7. 打开视频 App。
8. 等待悬浮按钮 `幕` 出现。
9. 看视频时点击 `幕` 暂停并展开菜单。
10. 点击 `译` 翻译字幕。
11. 点击 `学` 生成学习卡片。
12. 点击 `藏` 收藏学习卡片和完整截图。
13. 长按 `译` 或 `学` 可以回溯最近 2 秒缓存帧。

## 注意事项

部分视频 App 或 DRM 流媒体可能禁止截图，系统可能返回黑屏。

媒体暂停 / 播放依赖 Android 媒体按键事件，不保证所有播放器都支持。

OCR 识别效果会受到以下因素影响：

```text
字幕太小
字幕颜色和背景接近
视频压缩严重
画面过暗
字幕位置特殊
播放器 UI 遮挡
```

可以通过 `区` 菜单调整 OCR 字幕窗口，提高识别成功率。

## 技术栈

```text
Android 原生 Java
Foreground Service
MediaProjection 屏幕录制
ImageReader 截图
ML Kit Text Recognition
MiniMax Chat Completion API
GitHub Actions
Gradle
```

## 项目状态

这是一个通过 ChatGPT 5.5 Thinking 持续生成和迭代的实验性 Android 应用。

当前版本已经实现：

```text
悬浮窗
OCR
MiniMax 翻译
MiniMax 学习卡片
回溯缓存帧
手机 / 平板自适应布局
收藏完整截图
GitHub Actions 在线编译
```

后续仍可以继续优化：

```text
更强的播放器暂停控制
更精确的 OCR 区域可视化
收藏导出
字幕历史列表
多语言识别优化
更美观的学习卡片 UI
```

## 声明

本项目代码由 ChatGPT 5.5 Thinking 根据需求生成。  
项目使用者没有手写 Android 源码。  
构建过程通过 GitHub Actions 在线完成。  
项目仅用于个人学习、字幕理解和 AI 辅助编程实践。
