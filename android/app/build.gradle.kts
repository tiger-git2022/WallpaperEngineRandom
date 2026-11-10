import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号取 android/CHANGELOG.md 最上面一节「## [x.y.z] - 日期」；versionCode = major*10000 + minor*100 + patch
val changelogVersion: String = run {
    val file = rootProject.file("CHANGELOG.md")
    val line = if (file.exists()) file.readLines(Charsets.UTF_8).firstOrNull { it.startsWith("## [") } else null
    Regex("""^## \[(\d+\.\d+\.\d+)]""").find(line ?: "")?.groupValues?.get(1) ?: "0.0.1"
}
val appVersion = (findProperty("appVersion") as String?) ?: changelogVersion
// 测试包（-PtestBuild=true，上传到预发布 android-test）：记下编译时间，应用内更新据此判断 android-test 里的是不是更新的
val testBuild = (findProperty("testBuild") as String?) == "true"
val appCode = appVersion.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }.let { it[0] * 10000 + it[1] * 100 + it[2] }

// 发布签名：CI 用环境变量 WER_KEYSTORE（.jks 路径）和 WER_KEYSTORE_PASSWORD；本机读仓库外的
// ~/.android/werandom-workshop.properties（storeFile、password 两项）。两者都没有时用调试签名，只能自己装着试，
// 不能覆盖安装发布版。
val signing: Pair<File, String>? = run {
    val envFile = System.getenv("WER_KEYSTORE")
    val envPass = System.getenv("WER_KEYSTORE_PASSWORD")
    if (!envFile.isNullOrEmpty() && !envPass.isNullOrEmpty()) return@run File(envFile) to envPass
    val local = File(System.getProperty("user.home"), ".android/werandom-workshop.properties")
    if (!local.exists()) return@run null
    val p = Properties().apply { local.inputStream().use { load(it) } }
    val store = p.getProperty("storeFile") ?: return@run null
    val pass = p.getProperty("password") ?: return@run null
    File(store) to pass
}

android {
    namespace = "com.tiger.steamwallpaper"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.tiger.steamwallpaper"
        minSdk = 26
        targetSdk = 36
        versionCode = appCode
        versionName = appVersion
        buildConfigField("String", "REPO", "\"tiger-git2022/WallpaperEngineRandom\"")
        buildConfigField("boolean", "TEST_BUILD", testBuild.toString())
        buildConfigField("long", "BUILD_TIME", (if (testBuild) System.currentTimeMillis() else 0L).toString() + "L")
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = signing.first
                storePassword = signing.second
                keyAlias = "werandom"
                keyPassword = signing.second
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signing != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            // 同一把发布签名，调试版也能和发布版互相覆盖安装
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/kotlin/**", "DebugProbesKt.bin")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// 用运行 Gradle 的 JDK 编译（本机 Android Studio 自带的 JBR，CI 的 setup-java），不另外下载
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom-alpha:2026.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation(platform("io.coil-kt.coil3:coil-bom:3.6.3"))
    implementation("io.coil-kt.coil3:coil-compose")
    implementation("io.coil-kt.coil3:coil-gif")
    implementation("io.coil-kt.coil3:coil-network-okhttp")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.5.0"))
    implementation("com.squareup.okhttp3:okhttp")
}
