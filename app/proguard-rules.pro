# GlbParser - 公共字段被 FloatingWindowService 直接访问，不能混淆
-keep class com.jingxin.car3d.GlbParser { *; }

# GlbParser 内部类 Accessor
-keepclassmembers class com.jingxin.car3d.GlbParser$Accessor { *; }
