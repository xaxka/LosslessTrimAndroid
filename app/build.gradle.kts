import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 版本号规则（Asia/Shanghai 时区，按构建时刻生成）：
//   versionName = YY.MM.DD             例 26.08.19
//   versionCode = YYMMDDHHt（9 位整数）例 260819143（t = 分钟的十分钟位 0-5）
// CI 通过 -PversionName / -PversionCode 显式传入（保证一次流水线内产物一致）；
// 本地构建未传参时按当前时间生成。versionCode 上限 991231235 < 2100000000，合法。
fun devVersionName(): String =
    ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("yy.MM.dd"))

fun devVersionCode(): Int {
    val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
    val base = now.format(DateTimeFormatter.ofPattern("yyMMddHH")).toInt()
    return base * 10 + now.minute / 10
}

android {
    namespace = "com.xixka.losslesstrim"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xixka.losslesstrim"
        minSdk = 26
        targetSdk = 34
        versionCode = findProperty("versionCode")?.toString()?.toInt() ?: devVersionCode()
        versionName = findProperty("versionName")?.toString() ?: devVersionName()
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore.jks")
            storePassword = "losstrim123"
            keyAlias = "losstrim"
            keyPassword = "losstrim123"
        }
    }

    buildTypes {
        release {
            // R8 代码压缩 + 资源压缩（防崩溃的混淆边界规则见 app/proguard-rules.pro，
            // 关键是 ffmpeg-kit JNI 整包保留；Room/Compose/协程均已自带 consumer 规则）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // 图标只用核心集（ArrowBack/PlayArrow/Settings 等都在 core 内），弃用
    // extended（数千个图标类拖慢编译、debug 包臃肿）；Bookmark/Pause/SkipNext/
    // SkipPrevious 等 5 个不在 core 的图标在 ui/icons/ExtendedIcons.kt 本地补齐
    // （path 数据逐字取自 material-icons-extended 1.6.8 官方源码，几何一致）。
    // release 构建另有 R8 收缩，未引用图标本就不会进 dex。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ffmpeg-kit 社区维护版 fork（ffmpeg-kit 官方已归档）。换回 min 包：
    // 曾因 HEVC 抽帧"花屏"切到 full（+60MB native lib），后定位真正根因是
    // limited-range 视频直出 JPEG 的颜色范围错误，已由 ThumbStore 的
    // scale out_range=pc 修复（2026-08-23）；min 包内置 libavcodec 原生
    // h264/hevc 软解 + Android MediaCodec 硬解，剪辑走 -c copy 无需编解码器，
    // full 的体积开销不再值得。若再遇个别片源抽帧异常，优先查颜色范围/硬解回退链。
    implementation("com.antonkarpenko:ffmpeg-kit-min:2.2.1")

    // Room：探测结果/关键帧持久缓存（进程重启后免重扫，详见 data/CacheDb.kt）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
