// Корневая конфигурация VPN LAUNCHER (Android).
// Версии подобраны связкой: Gradle 8.13 + AGP 8.11.1 + Kotlin 2.1.20
// (см. PLAN-ANDROID.md, проверено по maven-метаданным 29.09.2026).
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}
