// :core — чистый Kotlin/JVM: порт vpn_launcher/core из vpn-launcher-py.
// Без Android-зависимостей -> тесты гоняются голой JVM (gradlew :core:test).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // api: типы kotlinx.serialization (JsonObject нод) входят в публичный
    // API (:app собирает/разбирает ноды) - на compile classpath потребителя
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // Фикстуры/golden скопированы из vpn-launcher-py/tests (см. tools/sync-golden.ps1)
    systemProperty("golden.dir", layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
}
