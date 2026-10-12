plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 有 keystore 就用正式的，没有就退回 debug 签名（照样能装）
val ksPath: String = System.getenv("ANDROID_KEYSTORE_PATH").orEmpty()
val hasKs: Boolean = ksPath.isNotEmpty() && file(ksPath).exists()

// ── 允许 CI 用 -P 参数覆盖（配合 workflow_dispatch 手动构建）──
//   -PvName=1.0.3       覆盖 versionName
//   -PvCode=4           覆盖 versionCode
//   -PabiFilters=arm64-v8a,armeabi-v7a   限制 ABI
//   -PsplitAbi=true     按 ABI 分包（体积更小，但需按机型装对应包）
val pVName: String? = (project.findProperty("vName") as String?)?.takeIf { it.isNotBlank() }
val pVCode: String? = (project.findProperty("vCode") as String?)?.takeIf { it.isNotBlank() }
val pAbi: String = (project.findProperty("abiFilters") as String?)?.trim().orEmpty()
val pSplitAbi: Boolean = (project.findProperty("splitAbi") as String?)?.lowercase() == "true"

android {
    namespace = "com.seagull.htv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seagull.htv"
        minSdk = 23
        targetSdk = 35
        versionCode = pVCode?.toIntOrNull() ?: 3
        versionName = pVName ?: "1.0.2"
        vectorDrawables.useSupportLibrary = true

        if (pAbi.isNotEmpty()) {
            ndk {
                abiFilters.clear()
                abiFilters.addAll(pAbi.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
    }

    splits {
        abi {
            isEnable = pSplitAbi
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasKs) {
            create("rel") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasKs) signingConfigs.getByName("rel")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module"
        )
        // gsyvideoplayer-ex_so 会塞进 IJK 播放器的 native 库（libijk*.so，四架构合计约 40MB），
        // 但我们用的是 ExoPlayer(media3)，压根不加载 IJK —— 实测这些 .so 占了 APK 的 75%。
        // 纯打包过滤，不动 classpath，零运行风险。
        jniLibs.excludes += setOf(
            "**/libijkffmpeg.so",
            "**/libijkplayer.so",
            "**/libijksdl.so"
        )
    }

    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 方案 B：原生列表 —— HTML 解析 + 原生图片加载 + 网络栈
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")

    // 实测 JitPack 坐标：14.0.0 存在，传递 exo2 -> androidx.media3 1.10.1
    implementation("com.github.CarGuo.GSYVideoPlayer:gsyvideoplayer:14.0.0")
}
