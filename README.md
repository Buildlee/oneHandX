<div align="center">

# OneHandX

**屏幕边缘的手势工作台**

<sub>Android 8.0+ · 无障碍浮层 · 全部本地运行 · 无网络权限</sub>

[![Download APK](https://img.shields.io/badge/download-v0.6.1_preview-18181b?style=flat-square&logo=android&logoColor=ffffff)](https://github.com/Buildlee/oneHandX/releases/tag/v0.6.1)

[![Release](https://img.shields.io/github/v/release/Buildlee/oneHandX?style=flat-square&label=release&labelColor=f4f4f5&color=e4e4e7)](https://github.com/Buildlee/oneHandX/releases)&nbsp;&nbsp;[![License](https://img.shields.io/badge/license-Apache--2.0-e4e4e7?style=flat-square&labelColor=f4f4f5)](LICENSE)&nbsp;&nbsp;[手势与动作](#手势与动作)&nbsp; · &nbsp;[从源码构建](#从源码构建)&nbsp; · &nbsp;[文档](docs/README.md)

</div>

<p align="center">
  <img src="docs/images/v060-home-light.png" width="185" alt="主界面浅色">
  <img src="docs/images/v060-home-dark.png" width="185" alt="主界面深色">
  <img src="docs/images/v060-mapping.png" width="185" alt="三方向手势映射">
  <img src="docs/images/v060-appearance.png" width="185" alt="曲面外观与透明度">
  <br><br>
  v0.6.0 模拟器截图：浅色总览 · 深色总览 · 手势映射 · 外观与反馈
</p>

## 这是什么

OneHandX 把屏幕左右边缘变成可编程的手势区：从边缘向内滑动即可触发动作。每个手柄 10 个槽位，35 个动作可选，全部本地运行。

当前试用版 **v0.6.1** 在三栏导航、独立配色和动作搜索的基础上，统一页面、弹层、控件与曲面收回动效，支持快速打断和减少动态效果。已完成模拟器验证；这一版尚未在 OPPO 等真机复测。稳定版 v0.5.1 仍保留在 Releases。

三条设计取向：

- **不抢系统的手势区**：手柄默认内移 26dp，系统返回手势与自定义手势各管一段。

- **不做系统级侵入**：一个无障碍服务加浮层窗口即可，不需要 root、Shizuku，也不改系统设置。

- **不替用户预设**：所有映射可改，配置可整体导出与恢复。

> **独立实现声明**：本项目与三星电子无关联，未使用其代码、安装包或美术素材。"One Hand Operation+"、"One UI"、"Good Lock" 是三星电子的产品名 / 商标，仅用于说明功能背景。详见 [许可与边界](#许可与边界)。

## 快速开始

1. 从 [v0.6.1 试用版](https://github.com/Buildlee/oneHandX/releases/tag/v0.6.1) 下载 APK（debug 签名，与 v0.5.1 / v0.6.0 相同）。
2. 安装后打开应用，按提示开启无障碍服务「OneHandX 手势服务」。
3. 从屏幕左右边缘向内滑动，先试用默认映射；位置、边距、槽位映射都在应用内调整。
4. 自行编译见[从源码构建](#从源码构建)。

> 覆盖安装会解绑无障碍服务，更新后需要重新开启一次。应用不申请网络权限。

## 主要能力

- **手势槽位**：每个手柄 10 个。短滑、长滑、滑动保持（滑入后停住）三个长度 × 直滑、斜上、斜下三个方向，另加一个长按。
- **多手柄**：左右各最多 3 个，长度、垂直位置、不透明度独立设置；边距与触摸宽度共用，「对齐触钮」可同步左右长度与位置。
- **动作库**：35 个动作。返回、主屏、最近任务、上一应用、通知面板、快捷设置、截图、锁屏、电源菜单、手电筒、音量 ±、音量面板、静音、媒体控制、屏幕超时、旋转、导航栏切换、颜色反转、搜索、打开应用、语音助手、虚拟触控板、浮动导航等。
- **浮窗工具**：快速工具（亮度条加自定义按钮）、快速启动器、任务切换器、虚拟触控板（移动 / 点击 / 长按）、浮动导航按钮。
- **场景适配**：键盘弹出时手柄移到键盘上方、隐藏或不处理（三模式）；主屏时可隐藏触钮；可按应用停用。
- **识别参数**：长滑距离、手势角度（15–60°）、长滑按住时长、越过阈值立即执行（短滑与长滑分别开关）、振动与强度。
- **外观与反馈**：贴边曲面、细线箭头、圆形箭头、弧面箭头、柔和曲线、极简指示。曲面背景、箭头、轮廓分别设色，背景透明度 0–100%；试滑区与真实浮层共用原生绘制器。
- **界面**：手势 / 面板 / 设置三栏，短滑与长滑分别编辑，动作搜索与分类，跟随系统 / 浅色 / 深色和减少动态效果。
- **备份**：导入前校验格式与类型，预览变更数量和缺失应用，再确认覆盖；保留旧版映射和样式设置。
- **高刷新率**：手柄与反馈窗口声明 `preferredRefreshRate`。v0.5 曾在 OPPO 上验证 144Hz；v0.6 的新反馈尚未在高刷真机复测。

## 手势与动作

每个手柄的槽位模型：

| 长度 \ 方向 | 直滑 | 斜上 | 斜下 |
| --- | --- | --- | --- |
| **短滑** | 直滑 | 斜上 | 斜下 |
| **长滑** | 长滑 · 直滑 | 长滑 · 斜上 | 长滑 · 斜下 |
| **滑动保持** | 保持 · 直滑 | 保持 · 斜上 | 保持 · 斜下 |

长按是独立槽位，默认按住 500ms 触发。

默认映射（左右手柄相同）：

| 手势 | 默认动作 |
| --- | --- |
| 短滑 · 直滑 | 返回 |
| 短滑 · 斜上 | 主屏 |
| 短滑 · 斜下 | 最近任务 |
| 其余槽位 | 无 |

所有槽位都能在应用内改成任意动作；分屏与切换输入法受系统版本限制，见 [已知限制](#已知限制)。

## 与系统手势共存

手势导航下，系统优先占用屏幕最外侧约 24dp 的滑动。ColorOS 实测：起点 ≤84px 由系统接管，≥91px 正常送达。OneHandX 的做法是把手柄默认内移 26dp，让两者各管一段：

- 边距可在 4–80dp 之间调整（手柄编辑页）；
- 希望完全贴边时，可改用三键导航，再用应用内的「浮动导航按钮」补回返回、主屏、最近任务；
- 通过 ADB 关闭系统返回热区（`back_gesture_inset_scale_*`）的做法在 Pixel 与三星上有效，在 ColorOS / OxygenOS 上实测无效，因此没有做成默认能力。

完整实测过程与证据见 [边缘手势冲突研究](docs/边缘手势冲突研究.md)。

## 状态与验证

**v0.6.0**：Android 12L / API 32 模拟器，450dp 常规字体与 320dp、1.3 倍字体，深浅主题四种组合各通过 56 项检查。构建与 Lint 通过；检查内容、真实手势服务及尚未覆盖项见 [v0.6 验证记录](docs/v0.6-验证记录.md)。

以下为 **v0.5 历史验证**（OPPO Find X10 Pro Max，ColorOS 17 / Android 17），不代表 v0.6 已在该设备复测：

- 手势回归：直滑返回、斜上主屏、斜下最近任务；26dp 边距的边界行为（84px 被系统接管，91px 起正常）。
- 高刷：`preferredRefreshRate=144`，滚动 581 帧，卡顿 0.69%，帧间隔中位 10ms。
- 深 / 浅双主题、Android 15+ edge-to-edge 系统栏、底部弹层安全区。
- 模拟器（Android 16 / API 36）回归通过。

尚未验证：

- 其他 OEM 与第三方 ROM（返回手势热区宽度、浮窗与后台策略差异）。
- 分屏、切换输入法在 Android 12 及以上的可用性，系统已收紧且实测无效。
- 平板、折叠屏、多用户与工作资料场景。

## 从源码构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle / AGP | 8.11.1（wrapper）/ 8.7.2 |
| compileSdk / minSdk / targetSdk | 35 / 26 / 35 |
| 语言 | Java，无 Kotlin 依赖 |
| 运行依赖 | androidx.appcompat 1.7.0 |

## 项目结构

```text
OneHandPlus/
├── app/src/main/java/io/buildlee/onehand/
│   ├── EdgeService.java          无障碍服务：手柄窗口、手势识别、动作执行
│   ├── EdgeHandleView.java       手柄绘制与触摸反馈
│   ├── GestureAction.java        动作表
│   ├── Prefs.java                配置与 JSON 备份
│   ├── MainActivity.java         主界面
│   ├── HandleEditActivity.java   手柄编辑：尺寸、位置、槽位映射
│   ├── ActionDialog.java         动作选择弹层
│   ├── UiKit.java / SoftSwitch.java / SoftSlider.java / Motion.java
│   ├── TouchpadOverlay.java      虚拟触控板
│   ├── FloatNavOverlay.java      浮动导航按钮
│   └── NavbarHelper.java         导航栏实验能力
├── app/src/main/res/             布局、双主题色板、图标、动效
├── docs/                         交付说明、调研记录、验证记录、截图
└── gradle/                       Gradle wrapper 8.11.1
```

## 已知限制

- **分屏、切换输入法**：Android 12 起系统收紧接口，Android 16 实测无效。
- **隐藏导航栏、颜色反转**：依赖 `policy_control` 等历史机制，新版本可能无效，需要一次 ADB 授权。
- **系统返回手势热区**：见[与系统手势共存](#与系统手势共存)。
- **OEM 私有能力**：弹出视图、最小化弹窗、AI 多截图、录屏、单手模式、游戏助推器等需要系统特权，未实现。

## 文档

| 文档 | 内容 |
| --- | --- |
| [交付说明](docs/交付说明.md) | 功能清单、安装使用、验证记录、版本历史 |
| [真机调研记录](docs/真机调研记录.md) | 三星 S23 Ultra 官方应用的功能实测与 50 动作对照 |
| [边缘手势冲突研究](docs/边缘手势冲突研究.md) | 返回手势热区机制、ADB 可调性、ColorOS 全局动作缺陷 |
| [参考项目研究](docs/参考项目研究.md) | Fluid NG、Akış、XGesture、EdgeGesture 的实现对照 |
| [v0.5 验证记录](docs/v0.5-验证记录.md) | 真机逐项验证与证据 |
| [v0.6 验证记录](docs/v0.6-验证记录.md) | 三栏导航、原生曲面、配置兼容与模拟器检查 |
| [UI 概念稿](docs/mockup.html) | 已签核的界面方向，浏览器打开 |

## 许可与边界

代码以 [Apache License 2.0](LICENSE) 开源；第三方组件见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 与 [NOTICE](NOTICE)。

本项目是独立实现，与三星电子无任何关联，未使用其代码、安装包或美术素材。"One Hand Operation+"、"One UI"、"Good Lock" 是三星电子的产品名 / 商标，仅用于说明功能背景。Apache-2.0 不授予商标使用权，请勿将上述名称或标识用作本项目名称与宣传标识。
