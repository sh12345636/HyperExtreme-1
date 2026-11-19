<p align="center">
  <img
    src="app/src/main/res/drawable-nodpi/cover.png"
    alt="COVER"
    width="95%"
    style="height: auto;"
  >
</p>

<p align="center">
  <img src="https://img.shields.io/github/v/release/22Frederic/HyperExtreme?style=flat" alt="release">
  <img src="https://img.shields.io/badge/Platform-Android-blue?style=flat&logo=android" alt="platform">
  <img src="https://img.shields.io/badge/minSdk-33-red?style=flat" alt="minSdk">
  <img src="https://img.shields.io/badge/targetSdk-37-green?style=flat" alt="targetSdk">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-yellow?style=flat" alt="license">
</p>

## 请注意

请勿在未经许可的情况下私自分发模块安装包，分享时，请引导至官方Github或网盘链接。对于使用非官方Github或网盘链接途径下载以及非官方签名的模块安装包造成的任何问题均不受理。

## 介绍

HyperExtreme是一款LSPosed美化模块，支持空间壁纸、光栅壁纸、Alive壁纸等功能，部分功能支持免Root运行。

## 兼容性

对于空间壁纸、光栅壁纸：LSPosed模式只在澎湃OS4上测试，也只会对澎湃OS4适配，**不受理任何澎湃OS4以外系统的使用问题**；系统壁纸服务（免Root）模式理论支持到安卓13，但对安卓17之外的安卓版本未测试。

对于Alive壁纸与其他功能：都需要Root与LSPosed，只会对澎湃OS4适配，**不受理任何澎湃OS4以外系统的使用问题**。

### 澎湃 OS3 已知差异（2026-11-19 实测）

真机：Redmi Note 12 Turbo / OS3.0.5.0.VMRCNXRK / Android 15 / LSPosed 2.2.1 / 模块 1.0 Beta6。

| 现象 | 原因（实测） |
| --- | --- |
| 空间/光栅壁纸**主体能显示**，但解锁淡出、AOD 压暗、撤前景层、视差模糊同步不生效 | SystemUI 里 `com.android.keyguard.panel.KeyguardPanelViewController` 不存在，同类实现拆到了 `com.android.keyguard.injector.KeyguardPanelViewInjector` 与 `com.android.systemui.shade.MiuiNotificationPanelViewController`；日志里是 32 条「未找到」 |
| 锁屏景深遮挡解除无效 | `com.android.keyguard.depth.KeyguardDepthInteractor` 整类不存在，`updateAvoidStatus` / `setDepthTransitionAlpha` 在本 ROM 零命中（该能力在 OS3 上无对应实现，只能显式停用） |
| 景深主体（抠像）失败，run_log 写「服务返回空位图」 | AICR 4.13.6（模块对标 4.13.10）：插件 binder 就绪、`segmentByFd` 返回空列表；广播通道的失败原因被 binder 覆盖，无法定位 |
| 视差一直「推图失败（bg=false）」 | 没有自定义背景、也没有 `crop_subject` 时脚本无输出，被判成失败（其实是"没有内容可推"） |

本分支（`hyperos3/**`）只做**跨版本共存**的修法：类解析加 fallback 表、按名字挂重载、把告警说清楚；**不改**任何 OS4 上的既有行为。

## 使用说明

对于需要LSPosed的功能，请先提前勾选并重启所有推荐作用域（初次安装及模块更新时），再使用对应功能。

本模块可能对修改较多的HyperOS ROM或修改过的推荐作用域应用不生效甚至导致其他问题。

本模块可能与其他修改相同作用域的LSPosed模块产生冲突，在使用模块出现问题时，请先排查模块冲突。

**目前对HyperMusicCover已初步完成适配。**

## 开源代码许可

本项目采用AGPL-3.0许可证，有关开源代码许可请在模块的关于页查看。
