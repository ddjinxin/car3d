Car3D
An application generated entirely through conversational prompts with TeleClaw, without reviewing the actual code. It is a 3D car model viewer based on Android's floating window system and OpenGL ES. It loads GLB-format 3D model files and renders them as an overlay on screen, supporting multi-model switching and drag-to-reposition.

Features
3D Model Rendering: Parses and renders GLB-format 3D models using OpenGL ES
Floating Window Display: Renders as a System Alert Window overlay on top of other apps
Multi-Model Switching: Supports loading multiple GLB models, with button-triggered sequential switching
Drag-to-Reposition: Touch-draggable floating window for free positioning
Auto-Rotation: Models rotate automatically without manual interaction
Texture Mapping: Full support for embedded GLB texture rendering
Foreground Service: Prevents the system from killing the app via foreground Service mechanism
Low Resource Usage: Zero third-party dependencies; the obfuscated Release APK is only 66KB
Technical Architecture
Component	Description
MainActivity	Entry Activity, handles permission checks and service startup
FloatingWindowService	Core service, manages floating window creation and OpenGL ES rendering
GlbParser	GLB file parser, extracts model vertices, normals, texture coordinates, and texture data
Rendering Engine: OpenGL ES 2.0 + EGL14 + TextureView
Animation Driver: Choreographer VSync callback, smooth 60fps rendering
Architecture: Service + WindowManager system floating window
Model Files
Storage Location
Place GLB model files in the device's Download directory:

/sdcard/Download/1.glb
/sdcard/Download/2.glb
/sdcard/Download/3.glb
...
Files follow a numeric naming convention (1.glb, 2.glb, 3.glb...), with no upper limit on quantity.

Switching Models
Tap the switch button on the left side of the floating window to cycle through models in numerical order. If the next numbered file does not exist, it automatically wraps back to 1.glb.

Compatibility
Android Version	Status	Notes
Android 6.0 (API 23)	Supported	minSdk 23
Android 7.0-7.1 (API 24-25)	Supported	Floating window uses TYPE_PHONE
Android 8.0+ (API 26+)	Supported	NotificationChannel creation required
Android 10 (API 29)	Supported	requestLegacyExternalStorage compatibility
Android 11+ (API 30+)	Supported	Uses MANAGE_EXTERNAL_STORAGE permission
Android 13+ (API 33+)	Supported	Dynamic POST_NOTIFICATIONS permission request
Android 14 (API 34)	Supported	foregroundServiceType passed to startForeground
Permissions
Permission	Purpose
SYSTEM_ALERT_WINDOW	Display floating window above other apps
FOREGROUND_SERVICE	Foreground service to keep app alive
FOREGROUND_SERVICE_DATA_SYNC	Android 14 foreground service type declaration
POST_NOTIFICATIONS	Android 13+ notification permission
READ_EXTERNAL_STORAGE	Read GLB model files
MANAGE_EXTERNAL_STORAGE	Android 11+ full file access permission
Build Requirements
Android Studio (latest version recommended)
JDK 1.8+
Android SDK, compileSdk 34
No third-party dependencies, pure native Android development
Project Structure
Car3D/
├── app/
│   ├── build.gradle              # App build configuration
│   ├── proguard-rules.pro        # R8 obfuscation rules
│   └── src/main/
│       ├── AndroidManifest.xml   # Permissions and component declarations
│       ├── java/com/jingxin/car3d/
│       │   ├── MainActivity.java        # Entry point, permission checks
│       │   ├── FloatingWindowService.java  # Floating window + OpenGL rendering (core)
│       │   └── GlbParser.java           # GLB file parser
│       └── res/
│           ├── layout/activity_main.xml  # Activity layout
│           ├── mipmap-*/ic_launcher.png   # App icon
│           └── values/
│               ├── strings.xml           # String resources
│               ├── colors.xml            # Color resources
│               └── styles.xml            # Theme styles
├── build.gradle                  # Project-level build configuration
├── gradle.properties             # Gradle properties
├── settings.gradle               # Module configuration
├── gradlew / gradlew.bat         # Gradle Wrapper
└── .gitignore                    # Git ignore rules
ProGuard Rules
R8 obfuscation is enabled (minifyEnabled + shrinkResources), with the following keep rules configured:

GlbParser and its inner class Accessor: public fields are directly accessed by the renderer
R$drawable: Resource file references
APK Size
Build Type	Size
Debug	~260KB
Release (R8 obfuscated)	~66KB
Use Case
Designed for LeCo Auto (car head unit launcher) users. Displays 3D car models as a floating window overlay on the car infotainment system, for desktop decoration and personalization.

Demo
https://pd.qq.com/s/5smopu79k

Developer
Jingxin (静心)

License
Personal project, for learning and non-commercial use only.

AI-generated

-------------------- 


# 车辆3D (Car3D)

一个纯用teleclaw在对话中生成的应用，没有看过具体代码。基于 Android 悬浮窗 + OpenGL ES 的 3D 车模型展示应用。加载 GLB 格式的 3D 模型文件，以悬浮窗形式在屏幕上渲染展示，支持多模型切换和拖动定位。

## 功能特性

- **3D 模型渲染**：使用 OpenGL ES 解析和渲染 GLB 格式的 3D 模型
- **悬浮窗展示**：以系统悬浮窗（System Alert Window）形式覆盖在其他应用上层显示
- **多模型切换**：支持加载多个 GLB 模型，点击按钮循环切换
- **拖动定位**：悬浮窗支持触摸拖动，可自由调整位置
- **自动旋转**：模型自动旋转展示，无需手动操作
- **纹理贴图**：完整支持 GLB 内嵌纹理贴图渲染
- **前台保活**：通过前台 Service 机制防止系统杀掉应用
- **低资源占用**：无第三方依赖，Release 混淆后 APK 仅 66KB

## 技术架构

| 组件 | 说明 |
|------|------|
| MainActivity | 入口 Activity，负责权限检查和服务启动 |
| FloatingWindowService | 核心服务，管理悬浮窗创建和 OpenGL ES 渲染 |
| GlbParser | GLB 文件解析器，解析模型顶点、法线、纹理坐标和贴图数据 |

- **渲染引擎**：OpenGL ES 2.0 + EGL14 + TextureView
- **动画驱动**：Choreographer VSync 回调，60fps 流畅渲染
- **架构模式**：Service + WindowManager 系统悬浮窗

## 模型文件

### 放置位置

将 GLB 模型文件放在手机下载目录：

```
/sdcard/Download/1.glb
/sdcard/Download/2.glb
/sdcard/Download/3.glb
...
```

文件命名规则为数字编号（1.glb、2.glb、3.glb...），无数量上限。

### 切换模型

点击悬浮窗左侧的切换按钮，按编号顺序循环切换。如果下一个编号的文件不存在，自动回到 1.glb。

## 兼容性

| Android 版本 | 兼容状态 | 说明 |
|-------------|---------|------|
| Android 6.0 (API 23) | 支持 | minSdk 23 |
| Android 7.0-7.1 (API 24-25) | 支持 | 悬浮窗使用 TYPE_PHONE |
| Android 8.0+ (API 26+) | 支持 | 创建 NotificationChannel |
| Android 10 (API 29) | 支持 | requestLegacyExternalStorage 兼容 |
| Android 11+ (API 30+) | 支持 | 使用 MANAGE_EXTERNAL_STORAGE 权限 |
| Android 13+ (API 33+) | 支持 | 动态申请 POST_NOTIFICATIONS 权限 |
| Android 14 (API 34) | 支持 | startForeground 传入 foregroundServiceType |

## 权限说明

| 权限 | 用途 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 在其他应用上层显示悬浮窗 |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14 前台服务类型声明 |
| `POST_NOTIFICATIONS` | Android 13+ 通知权限 |
| `READ_EXTERNAL_STORAGE` | 读取 GLB 模型文件 |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+ 所有文件访问权限 |

## 构建要求

- Android Studio（推荐最新版）
- JDK 1.8+
- Android SDK，compileSdk 34
- 无第三方依赖，纯原生 Android 开发


## 项目结构

```
车辆3D/
├── app/
│   ├── build.gradle              # 应用构建配置
│   ├── proguard-rules.pro        # R8 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 权限和组件声明
│       ├── java/com/jingxin/car3d/
│       │   ├── MainActivity.java        # 入口，权限检查
│       │   ├── FloatingWindowService.java  # 悬浮窗 + OpenGL 渲染（核心）
│       │   └── GlbParser.java           # GLB 文件解析器
│       └── res/
│           ├── layout/activity_main.xml  # Activity 布局
│           ├── mipmap-*/ic_launcher.png   # 应用图标
│           └── values/
│               ├── strings.xml           # 字符串资源
│               ├── colors.xml            # 颜色资源
│               └── styles.xml            # 主题样式
├── build.gradle                  # 项目级构建配置
├── gradle.properties             # Gradle 属性
├── settings.gradle               # 模块配置
├── gradlew / gradlew.bat         # Gradle Wrapper
└── .gitignore                    # Git 忽略规则
```

## ProGuard 混淆规则

项目开启了 R8 混淆（minifyEnabled + shrinkResources），已配置以下 keep 规则：

- `GlbParser` 及其内部类 `Accessor`：公共字段被渲染器直接访问
- `R$drawable`：资源文件引用

## APK 大小

| 构建类型 | 大小 |
|---------|------|
| Debug | ~260KB |
| Release（R8 混淆） | ~66KB |

## 使用场景

专为乐酷车机桌面（LeCo Auto）用户设计，在车机系统上以悬浮窗形式展示 3D 车辆模型，用于桌面装饰和个性化展示。

## 演示地址##

https://pd.qq.com/s/5smopu79k

## 开发者

静心

## 许可

个人项目，仅供学习交流。

> AI生成


