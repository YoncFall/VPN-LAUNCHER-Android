// Доступ к фикстурам/golden в test-ресурсах (общее для golden-тестов).
// Golden сняты PS 5.1 (tools/make_golden.ps1 в vpn-launcher-py) — UTF-8 c BOM.
package com.yoncfall.vpnlauncher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Якорь для загрузки ресурсов test-класслоадера. */
internal object GoldenResources

private fun resourceBytes(path: String): ByteArray =
    requireNotNull(GoldenResources::class.java.getResourceAsStream(path)) { "нет ресурса $path" }
        .use { it.readBytes() }

/** Текст тестового ресурса (UTF-8). */
fun fixtureText(name: String): String = String(resourceBytes("/fixtures/$name"), Charsets.UTF_8)

/** Golden-эталон PS 5.1 как JsonElement (BOM снимается перед парсером). */
fun goldenElement(name: String): JsonElement =
    Json.parseToJsonElement(String(resourceBytes("/golden/$name"), Charsets.UTF_8).removePrefix("\uFEFF"))
