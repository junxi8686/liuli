import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Each agent builds into its own directory (`-PliuliBuildDir=build-t1`) so
// parallel verification runs never fight over one outputs folder.
layout.buildDirectory.set(
    rootProject.layout.projectDirectory.dir(
        providers.gradleProperty("liuliBuildDir").getOrElse("build")
    )
)

// One place to bump the version: `version.properties` at the repo root. Reading
// it here rather than hard-coding keeps `versionCode` honest — Android refuses
// to install over an APK with the same code, so a stale constant silently turns
// every install into "uninstall first, and lose your data".
val liuliVersion: Properties = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** `琉璃-1.2.0.apk` instead of `app-release.apk`, so builds are tellable apart. */
val liuliApkName: String = "琉璃-${liuliVersion.getProperty("versionName", "0.0")}"

android {
    namespace = "com.liuli.btchat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.liuli.btchat"
        minSdk = 31
        targetSdk = 36
        versionCode = liuliVersion.getProperty("versionCode", "1").toInt()
        versionName = liuliVersion.getProperty("versionName", "1.0")
    }

    // 签名密钥**不进仓库**。
    //
    // 它们曾经是提交进来的（为了让别人能复现出可安装的包），但公开仓库里放私钥
    // 和口令等于把「谁能发布能覆盖安装的琉璃更新」交出去：同一个包名，只有用同一
    // 把密钥签的包才装得上去。演示项目的可复现性不值这个价。
    //
    // 本地构建照旧：`keystore/liuli-release.jks` 和 `keystore/keystore.properties`
    // 都在 .gitignore 里。两者齐备就签名，缺任一个就产出未签名包 —— 构建不会失败，
    // 只是 `assembleRelease` 出来的 APK 需要你自己签。
    val keystorePropsFile = rootProject.file("keystore/keystore.properties")
    val keystoreFile = rootProject.file("keystore/liuli-release.jks")
    val signingProps = if (keystoreFile.exists() && keystorePropsFile.exists()) {
        // `Properties()` 直接用，不要写 `java.util.Properties()` —— 在 Gradle 的
        // Kotlin 脚本里 `java` 已经被解析成 Gradle 的 java 扩展，全限定名会
        // "Unresolved reference 'util'"。
        Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
    } else {
        null
    }

    signingConfigs {
        if (signingProps != null) {
            create("liuli") {
                storeFile = keystoreFile
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias", "liuli")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 strips the unused two-thirds of material-icons-extended and
            // the unused halves of Compose/Media3, which is most of the APK.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingProps?.let { signingConfigs.getByName("liuli") }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The 我的 tab reads BuildConfig.VERSION_NAME.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // AGP 9.4.1 ships a lint that throws while building Kotlin 2.3.21 FIR
        // property symbols (KaFirKotlinPropertyKtParameterBasedSymbol), which
        // takes down `assembleRelease` with an internal error rather than a
        // finding. The release gate is therefore off; run `gradlew :app:lint`
        // by hand when a report is actually wanted.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * Copies the signed release APK into `dist/` under its real version, so the
 * folder tells you what you are looking at instead of five files all called
 * `app-release.apk`. AGP's own output name is left alone on purpose — renaming
 * it means reaching into `VariantOutputImpl`, which is internal and moves
 * between AGP releases.
 */
tasks.register<Copy>("publishDist") {
    group = "build"
    description = "把签名发布包复制到 dist/琉璃-<版本>.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "$liuliApkName.apk" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    // Compose is pinned explicitly to the versions present in the offline cache.
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-graphics:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.foundation:foundation-layout:1.11.4")
    implementation("androidx.compose.runtime:runtime:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // Liquid glass
    implementation("io.github.kyant0:backdrop:2.0.0")
    implementation("io.github.kyant0:shapes:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Media
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")

    testImplementation("junit:junit:4.13.2")
}
