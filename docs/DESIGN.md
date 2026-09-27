# 设计与实现笔记

本文记录 ClipLink 的关键技术决策、踩过的坑和验证方法，供想深入了解或参与开发的读者阅读。
使用层面的介绍见 [README](../README.md)。

## 目录

1. [为什么必须用 Shizuku](#1-为什么必须用-shizuku)
2. [双通道检测：事件监听 + 看门狗轮询](#2-双通道检测事件监听--看门狗轮询)
3. [与 IClipboard 的反射交互](#3-与-iclipboard-的反射交互)
4. [兜底路径：透明 Activity 抢焦点](#4-兜底路径透明-activity-抢焦点)
5. [关于「已粘贴」系统提示](#5-关于已粘贴系统提示)
6. [界面适配：edge-to-edge 与深色模式](#6-界面适配edge-to-edge-与深色模式)
7. [应用图标：从参考位图到矢量](#7-应用图标从参考位图到矢量)
8. [测试与验证](#8-测试与验证)
9. [排查：复制了但没提醒](#9-排查复制了但没提醒)

---

## 1. 为什么必须用 Shizuku

Android 10 起，**后台应用无法读取剪切板**。这不是权限问题，而是系统在
`ClipboardService.clipboardAccessAllowed()` 里做的硬性检查：只有以下几类调用者能读到内容，
其余一律返回 `null`：

- 默认输入法
- 当前持有焦点的应用
- ContentCapture 服务
- 自动填充服务
- 持有 `READ_CLIPBOARD_IN_BACKGROUND` 权限者

**关键点：无障碍服务不在这个豁免名单里。** 网上大量教程说"开个无障碍服务就能后台监听
剪切板"，这在原生 Android 10+ 上不成立——AOSP 源码从 10 到 15 都没有这一项。

而 shell 账号（`com.android.shell`，uid 2000）**恰好持有**
`READ_CLIPBOARD_IN_BACKGROUND`。Shizuku 的 ADB 模式就是让应用以这个身份调用系统服务。
所以本应用用 Shizuku 换取真正的后台监听能力：

- `getPrimaryClip()` 在后台也能读到真实内容
- `addPrimaryClipChangedListener()` 的回调真正触发（它的权限检查在**派发时**进行，
  shell 身份能通过）

结果就是**事件驱动的后台监测**：复制即触发，无需轮询、无需抢焦点、无闪屏，
也不会触发系统的「已粘贴」提示。

## 2. 双通道检测：事件监听 + 看门狗轮询

事件监听延迟低（复制后立即触发）、无轮询开销，但注册存在两个已知失效场景：

- `system_server` 重启（系统更新、手动重启）会丢失注册，且**不会报错**
- 部分 OEM 机型会让注册静默失败

因此服务里额外跑一个低速看门狗轮询作为保险：

| 情况 | 轮询间隔 | 作用 |
|------|---------|------|
| 监听注册成功 | 5 秒 | 仅作保险，几乎不耗电 |
| 监听注册失败 | 1.5 秒 | 承担全部检测职责 |

主界面的诊断区会显示「事件监听: 已注册 / 注册失败（已启用轮询兜底）」与
「剪切板读取: 正常 / 失败」，注册失败时还会附上具体异常类型与消息。
Shizuku 不可用时，应用降级为「点通知才检测」（见第 4 节），功能不静默失效。

## 3. 与 IClipboard 的反射交互

`IClipboard` 是系统隐藏类，不在公开的 `android.jar` 里，编译期无法 import，
所以全部访问走反射，监听器用动态代理实现。这一路有好几个必须遵守的细节：

### 手写 Binder 协议而不是 AIDL

AGP 的 AIDL 编译器（`compileDebugAidl`）在 Windows + 含中文的项目路径下会抛
`MalformedInputException`，且无法通过 `-Dfile.encoding` 规避。手写
`transact`/`onTransact` 功能等价，还去掉了该编译环节。对应代码：
`shizuku/ClipboardProtocol.kt`。

### 监听器必须用 `IOnPrimaryClipChangedListener$Stub.asInterface()` 包装

这是实际踩过的坑：初版用 `java.lang.reflect.Proxy` 构建监听器，结果是权限都给了、
状态也正常，但复制东西毫无反应。

原因：系统把监听器交给 `RemoteCallbackList.register()`，后者会立刻调用
`callback.asBinder()`，并在派发时于其持有的对象上做一次**本地 Java 方法调用**
`dispatchPrimaryClipChanged()`。`java.lang.reflect.Proxy` 生成的是普通 Java 对象、
**不是 Binder**，回调链路建立不起来。

正确做法是先经 `asInterface(binder)` 包成接口实例。系统侧拿到的是
`IOnPrimaryClipChangedListener$Stub$Proxy`，其方法体就是 `transact(...)`，
事件才会通过 Binder 回到本进程的 `onTransact`。日志中可确认：

```text
监听器对象已构建：android.content.IOnPrimaryClipChangedListener$Stub$Proxy
```

### Android 14+ 的 `deviceId` 必须传有效值

`addPrimaryClipChangedListener` 的参数在 Android 14 起变成
`(listener, String, String, int, int)`。其中 `deviceId` 若为无效值，AOSP 源码里会直接
`return` 而不注册。本应用传 0（`DEVICE_ID_DEFAULT`），`attributionTag` 传
`"clipboard"`，`userId` 传 0。已实测的实参序列：

```text
实参=[Proxy, String, String, Integer, Integer]
```

`getPrimaryClip` 在 Android 14/15 也增加了 `deviceId` 参数，读取时按参数类型
反射构造调用以兼容各版本。

### 两个连接不能混用

界面（`MainActivity`）和前台服务各持有独立的 `ShizukuHelper` 实例。两个必须遵守的
约束，否则监测会静默失效：

1. **界面查询状态必须用 `bindReadOnly()`。** UserService 侧只持有**单个**回调，
   若界面用 `bindUserService()` 绑定，会调 `setListener` 覆盖掉服务正在使用的回调。
2. **界面退出必须用 `unbindLocal()`。** `unbind()` 里包含 `proxy.destroy()`，
   而那是 `System.exit(0)`——会直接杀掉正在工作的 UserService 进程。

## 4. 兜底路径：透明 Activity 抢焦点

Shizuku 不可用时，应用降级为「点通知才检测」。检测由一个半透明无界面 Activity
（`ClipboardCheckActivity`）完成，几个关键点：

- **必须是 Activity。** 焦点是窗口属性——BroadcastReceiver 和 Service 根本没有窗口，
  无论怎么触发都拿不到 `isUidFocused()`。
- **主题必须是 translucent。** `Theme.Translucent.NoTitleBar` 能让 AOSP 的
  `validateStartingWindowTheme()` 跳过启动窗口，因此 Android 12+ 不会闪启动图标。
- **不能加 `FLAG_NOT_FOCUSABLE`。** 拿不到焦点就读不到内容。
- **读操作放在 `onWindowFocusChanged` 里。** 焦点是异步获得的，在 `onCreate()` 里读
  会拿到 `null`。同时设了 1.5 秒超时保护，避免焦点一直不来时窗口卡在屏幕上。

## 5. 关于「已粘贴」系统提示

在 Shizuku 路径下**不会**出现这个提示——因为读取发生在 shell 身份下，而系统只对
普通应用的读取弹提示。

兜底路径（点通知读取）会弹一次提示。这是系统行为，第三方应用无法规避：
唯一能抑制它的 `SUPPRESS_CLIPBOARD_ACCESS_NOTIFICATION` 是 `signature` 级权限。

## 6. 界面适配：edge-to-edge 与深色模式

### 全面屏（edge-to-edge）

Android 15（API 35）起，targetSdk ≥ 35 的应用被**强制** edge-to-edge：内容默认延伸到
状态栏与导航栏之后，且 `android:statusBarColor` / `android:navigationBarColor` 变为
空操作。不处理 inset 的话，顶部标题会被状态栏压住、底部按钮会被导航栏遮住。

处理逻辑集中在 `InsetsHelper`，两种模式：

| 模式 | 适用场景 | 做法 |
|------|---------|------|
| `applyToRoot` | 内容型页面（主界面） | 根布局加四边内边距，背景填满系统栏区域 |
| `applyWithToolbar` | 带固定 Toolbar 的页面（历史、设置） | Toolbar 加顶部内边距使背景延伸到状态栏；根布局加底部内边距把按钮推离导航栏 |

两个实现细节：

- **底部内边距加在容器上，不加在按钮上。** padding 是视图内部的内边距，加在按钮上
  只会让文字上移，按钮边界与可点击区域仍延伸进导航栏。
- **padding 基于初始值重算，而非累加。** 键盘弹出、旋转、切换深色模式都会重新分发
  inset，累加写法会导致留白越来越大。

不使用 `android:fitsSystemWindows="true"`——那是旧机制，与 edge-to-edge 配合时行为
不一致；也不用 `windowOptOutEdgeToEdgeEnforcement` 规避，该开关在后续版本会被移除，
且规避后布局在全面屏设备上会出现黑边。

### 深色模式

配色分两套，`values/colors.xml` 与 `values-night/colors.xml` 同名对应，
`Theme.Material3.DayNight` 按系统开关自动选择：

```text
com.cliplink:color/surface          → 亮色 0xffffffff / 夜间 0xff1e2023
com.cliplink:color/surface_variant  → 亮色 0xfff4f6fb / 夜间 0xff131517
```

**主题用 Base 模式拆分**，这是关键点：Android 的 style 在不同资源限定符下是
**整体替换**而非合并。若在 `values-night/themes.xml` 里重写整个 `Theme.ClipLink`，
会丢掉全部颜色定义。因此：

```text
Theme.ClipLink.Base     ← 共享部分（颜色、系统栏透明、forceDarkAllowed=false）
Theme.ClipLink          ← values/ 与 values-night/ 各一份，只覆盖明暗相关项
```

**`windowLightStatusBar` 的语义容易搞反**：它的含义是「状态栏背景为浅色，
请用深色图标」。

| 主题 | windowLightStatusBar | 效果 |
|------|---------------------|------|
| 亮色 | `true` | 深色图标配浅背景 |
| 夜间 | `false` | 浅色图标配深背景 |

写死为 `true` 会导致夜间状态栏图标与背景同色而不可见。

配色层次在两种模式下都成立：亮色下页面底色比卡片深，夜间下页面底色也比卡片深——
「底色衬托卡片」的关系一致。`forceDarkAllowed=false` 禁止系统自动反色，因为本应用
已完整适配深色。

验证：

```bash
adb shell cmd uimode night yes    # 开启深色
adb shell cmd uimode night no     # 关闭
```

切换后观察状态栏图标是否清晰、卡片与底色是否有层次、文字对比度是否足够。
全面屏验证需在**手势导航**模式下查看（三键导航占据底部空间，问题不明显）。

## 7. 应用图标：从参考位图到矢量

**全部是矢量**（VectorDrawable），源矢量是仓库根目录的 `icon.svg`。系统按当前屏幕
密度实时栅格化，任何分辨率下都清晰，APK 里不含位图。

| 文件 | 内容 |
|----|------|
| `drawable/ic_launcher_background.xml` | 铺满画布的对角渐变，17 个色标与坐标直接取自 `icon.svg` 的 `bg` 渐变 |
| `drawable/ic_launcher_foreground.xml` | 只含图形（白剪贴板 / 青链接 / 绿箭头），背景透明，外接矩形缩到画布 62% |
| `drawable/ic_launcher_monochrome.xml` | 同一图形的单色版，供 Android 13+ 主题图标取 alpha |

三者由 `mipmap-anydpi-v26/ic_launcher.xml` 组装成自适应图标。`minSdk` 是 26，
自适应图标在所有受支持的设备上都生效，因此不需要位图兜底——旧版按密度分桶的
五套位图已整体移除：`anydpi` 限定符的优先级高于任何密度限定符，这些位图在受支持
的设备上一张都不会被加载。

`icon.svg` 本身由一组脚本从参考位图逆向生成（亚像素轮廓提取 + 三次贝塞尔拟合 +
对角渐变采样）。这些生成与校验脚本未随仓库发布，需要时可从初始提交的 git 历史恢复。

**为什么把图形与背景拆成两层。** `icon.svg` 里图形的外接矩形是画布的 63.8% × 70.2%，
看着能塞进 66.7% 的安全区——但那是**矩形**口径，圆形遮罩可见的是一个内切圆；
按外接圆（对角线）算，图形占画布 **94.8%**，直接当前景铺满会把剪贴板顶部的挂夹和
绿色箭头切掉（位图版本实测确认）。拆开之后：

- 图形缩到 62% 落在安全区内，任何遮罩形状都不裁切
- 渐变交给背景层铺满，圆形遮罩边缘不会露出图层边界

62% 这个比例沿用自位图版本，所以换矢量前后图标的大小与位置不变：把渲染出的矢量前景
与旧位图逐像素比对，形状 IoU 97.6%、RGB 平均绝对差约 2/255（残差来自抗锯齿边缘），
边界框相差不超过 1 像素。

**主题图标用同一份图形的单色版**，而不是另画一套——两套形状不同会显得不统一。

一个校验时的坑：若把这些色值翻译成 CSS/SVG 做渲染比对，注意 Android 的 8 位色值
是 `#AARRGGBB`，而 CSS 是 `#RRGGBBAA`，直接搬过去蓝紫渐变会显示成粉橙。

## 8. 测试与验证

### 网址识别逻辑

`app/src/test/java/com/cliplink/UrlExtractorTest.kt` 包含 40+ 个用例，覆盖正常识别
（含中文句内提取、标点剥离、多 URL）与误判拦截（文件名、纯 IP、内网地址、非 http
协议、超长文本）。

`UrlExtractor` 刻意**不依赖 `android.util.Patterns`**——那是框架类，在 JVM 单元测试里
只返回桩值，会导致「测试通过但运行时行为不同」。自带纯 Kotlin 正则让两边行为一致。

### Gradle 单元测试的环境适配

Kotlin 1.9 插件在部分 JDK + AGP 组合下，会把单元测试类输出到
`build/tmp/kotlin-classes/<variant>`，但不把该目录注册进测试运行器 classpath，导致
运行时报 `ClassNotFoundException`。这是工具链组合缺陷，与代码无关。
`app/build.gradle.kts` 里已显式补上该目录（`tasks.withType<Test>` 配置），正常的
Android Studio 环境下 `./gradlew test` 可直接运行。

### 真机验证清单

1. 安装并启动 Shizuku
2. 打开 ClipLink，授予通知与 Shizuku 权限，点「开始监测」
3. 状态应显示「监测中」（绿点）
4. 点「测试当前剪切板」，先把一个网址复制到剪切板——应弹出通知
5. 复制一个新网址，通知栏应自动弹出「检测到网址」
6. 点「打开」，确认用默认浏览器跳转
7. 关闭 Shizuku，观察应用是否降级为兜底模式并给出提示
8. 重启手机，确认应用提示 Shizuku 需重新启动

## 9. 排查：复制了但没提醒

按下面顺序看：

1. **主界面诊断区**是否显示「事件监听: 已注册」和「剪切板读取: 正常」
2. 若「剪切板读取: 失败」→ Shizuku 权限已失效，重新在 Shizuku 里启动服务
3. 若「事件监听: 注册失败」→ 轮询兜底会接管，功能仍可用，但会有最多 1.5 秒延迟；
   界面会显示具体失败原因
4. 若两项都正常但仍无提醒 → 检查是否命中了「忽略域名」，或设置了「仅 Wi-Fi / 仅充电」
5. 用 `adb logcat -s ClipboardUserService ClipLinkApp ClipboardMonitor` 看详细日志。
   正常启动时应看到：环境（uid=2000）→ 监听器对象已构建（`$Stub$Proxy`）→
   重载签名 → 实参 → 注册调用成功 → 回读验证 → `间隔 5000ms（监听注册=true）`

## 10. 已知限制

**Shizuku 重启后需重新授权启动。** 应用会检测并在通知里提示，同时自动降级到兜底
路径。若要彻底避免，可在 Shizuku 中启用 root 模式的开机自启。

**厂商定制系统的额外限制。** 部分 ROM 需要额外设置：

- **小米 / HyperOS**：在「安全中心 → 权限 → 自启动」中允许，并关闭「MIUI 优化」对
  后台的限制
- **OPPO / ColorOS**：允许「自启动」，并将电池优化设为「不优化」
- **华为 / EMUI**：`PowerGenie` 会强杀非白名单进程，在「电池 → 应用启动管理」改为
  「手动管理」并全部勾选
- **三星 / One UI**：在「电池 → 后台使用限制」中移除「休眠应用」限制

已知 Shizuku 服务在一些机型（如 One UI 8 / Android 16）上会被系统在休眠唤醒后杀掉，
届时监听会中断并降级到兜底路径。
