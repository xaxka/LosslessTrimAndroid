# ============================================================================
# R8 混淆规则（配套 buildTypes.release 的 isMinifyEnabled + shrinkResources）
# 原则：只保住「运行期按名字查找」的边界（JNI / 反射），其余交给 R8 收缩；
# Room、Compose、DataStore、协程均自带 consumer 规则，勿重复手写。
# ============================================================================

# ---------- ffmpeg-kit：JNI 静态注册边界（混淆即崩，必须整包保留）----------
# native 层按「完整类名 + 方法名」静态注册 JNI 符号
# （Java_com_antonkarpenko_ffmpegkit_...），日志/统计/会话完成回调同样按名
# 查找 Java 方法。任何改名/删除都会在首次调用时抛 UnsatisfiedLinkError /
# NoSuchMethodError，因此整个包不参与混淆。
-keep class com.antonkarpenko.ffmpegkit.** { *; }
# AAR 内部对可选依赖（kotlin-stdlib 之外的平台类等）的引用告警兜底
-dontwarn com.antonkarpenko.ffmpegkit.**

# ---------- 崩溃堆栈可读性（线上定位问题必需）----------
# 保留源文件名与行号；mapping.txt 照常生成，可符号化还原
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- 泛型签名 / 注解 / 内部类元数据 ----------
# Room/DataStore/Compose 的反射兜底；缺失时个别路径会抛
# IllegalArgumentException / NoSuchMethodException
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# ---------- 枚举 valueOf / values（设置项按 name 存取 DataStore）----------
# proguard-android-optimize.txt 默认模板已含全局枚举规则，此处按包显式声明
-keepclassmembers enum com.xixka.losslesstrim.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- 备忘（核实过不需要的规则，避免后人盲加）----------
#   - Manifest 组件（Application/Activity/Service）：AGP 自动生成 keep 规则
#   - Room 实体：编译期生成实现类，无运行时反射
#   - Compose @Composable：编译器插件处理，material3 自带 consumer 规则
#   - kotlinx-coroutines / DataStore：AAR 内置 consumer 规则
#   - 应用代码已核查无 Class.forName / getIdentifier 动态查找
