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
            isMinifyEnabled = false
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ffmpeg-kit 社区维护版 fork（ffmpeg-kit 官方已归档）。切到 full 包：
    // min 包只含 demux/mux + stream copy，**不含 HEVC/H.264 软解码器**——抽
    // 帧时（-frames:v 1）触发解码但 fallback 解码器在 HEVC + B 帧 + 不
    // 标准 MP4 上系统性花屏（粉红条带 / 绿红紫混合）；full 包内置
    // x264/x265/dav1d 等完整软解码器，独立 I 帧可解无参考需求，必然稳定。
    // APK 体积约 +60MB（full vs min 的 native lib 差），个人自用工具可接受。
    implementation("com.antonkarpenko:ffmpeg-kit-full:2.2.1")

    // Room：探测结果/关键帧持久缓存（进程重启后免重扫，详见 data/CacheDb.kt）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
