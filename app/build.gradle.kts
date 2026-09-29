import java.util.Properties

// :app — Compose-оболочка + VpnService + libbox-мост.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Подпись release: rootProject/key.properties (в git не попадает, см. .gitignore).
// Файла нет или нет ключа storeFile -> release собирается без подписи
// (app-release-unsigned.apk). Keystore и пароль: README.txt рядом с кепкой.
val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.yoncfall.vpnlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yoncfall.vpnlauncher"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0" // общая версия продукта с desktop (как VPN LAUNCHER 2.0.0)
        // libbox.aar несёт все ABI; для debug оставляем два основных
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keyProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Релизные APK: universal (для сайта/«скачал и поставил») + по ABI
    // (arm64-v8a, armeabi-v7a) — «universal + splits, как SFA» (PLAN-ANDROID).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG — флаги libbox (fixAndroidStack и др.)
    }

    packaging {
        jniLibs {
            // .so из AAR распаковывать как обычные файлы (паттерн SFA/usgate)
            useLegacyPackaging = true
        }
    }

    sourceSets {
        // Kotlin-исходники приложения лежат в src/main/kotlin (как в :core)
        getByName("main").java.srcDir("src/main/kotlin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// libbox.aar: качается при сборке, в git НЕ коммитится (см. PLAN-ANDROID.md).
// Версия 1.14.1 = ветка движка desktop-версии (sing-box 1.14.x).
// sha256 (из релиза singbox-android/libbox):
//   93b2596c4e90df32463a9ade5c89d85f83a16aacd94928ba26fc4bce41eaf2a9
// Переопределение: -Plibbox.version=... или -Plibbox.aar.url=...
// ---------------------------------------------------------------------------
val libboxVersion = (project.findProperty("libbox.version") as String?) ?: "1.14.1"
val libboxUrl = (project.findProperty("libbox.aar.url") as String?)
    ?: "https://github.com/singbox-android/libbox/releases/download/$libboxVersion/libbox.aar"
val libboxAar = layout.projectDirectory.file("libs/libbox.aar")

val fetchLibbox by tasks.registering {
    group = "sing-box"
    description = "Скачать libbox.aar в app/libs/ (пропускается, если уже есть)"
    outputs.file(libboxAar)
    onlyIf { !libboxAar.asFile.exists() }
    doLast {
        val dest = libboxAar.asFile
        dest.parentFile.mkdirs()
        logger.lifecycle("Downloading libbox $libboxVersion from $libboxUrl")
        ant.invokeMethod(
            "get",
            mapOf("src" to libboxUrl, "dest" to dest, "skipexisting" to false)
        )
        require(dest.exists() && dest.length() > 1_000_000) {
            "libbox.aar download failed or too small. Положите AAR вручную: ${dest.absolutePath}"
        }
        logger.lifecycle("Saved ${dest.absolutePath} (${dest.length()} bytes)")
    }
}

tasks.named("preBuild").configure {
    dependsOn(fetchLibbox)
}

dependencies {
    // AAR движка (fetchLibbox)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Игровой стиль рисуем сами (как QSS на desktop)
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // подписка HTTP

    testImplementation("junit:junit:4.13.2")
}
