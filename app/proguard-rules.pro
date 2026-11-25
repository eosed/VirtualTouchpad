# R8 配置：本应用完全靠框架反射实例化四大组件（Activity / Service / TileService / Receiver），
# 类名既写在 AndroidManifest 里，也被 TileService、无障碍服务在运行时按名字查询，
# 因此保留这些入口类并禁止改名，内部字段和方法照常压缩。

-keep class com.vtp.touchpad.MainActivity { *; }
-keep class com.vtp.touchpad.PadAccessibilityService { *; }
-keep class com.vtp.touchpad.PadTileService { *; }
-keep class com.vtp.touchpad.ToggleActivity { *; }
-keep class com.vtp.touchpad.ToggleReceiver { *; }

# TileService 与无障碍服务由系统以类名反射加载，名字不能被混淆
-keepnames class com.vtp.touchpad.*

# 不保留行号信息以外的调试元数据，减小包体
-renamesourcefileattribute SourceFile
