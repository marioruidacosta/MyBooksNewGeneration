import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Test fixtures become Kotlin constants: common test code can't read resource files on iOS.
val generateTestFixtures by tasks.registering {
    val input = layout.projectDirectory.dir("src/commonTest/fixtures")
    val output = layout.buildDirectory.dir("generated/fixtures/commonTest")
    inputs.dir(input)
    outputs.dir(output)
    doLast {
        val encoder = Base64.getEncoder()
        val entries = input.asFile.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
            .joinToString("\n") { "    \"${it.name}\" to \"${encoder.encodeToString(it.readBytes())}\"," }
        val dollar = "$"
        val source = listOf(
            "package com.mcosta.mybooks.testing",
            "",
            "import kotlin.io.encoding.Base64",
            "",
            "private val fixtures: Map<String, String> = mapOf(",
            entries,
            ")",
            "",
            "fun fixtureBytes(name: String): ByteArray = Base64.decode(fixtures[name] ?: error(\"missing fixture ${dollar}name\"))",
            "",
            "fun fixture(name: String): String = fixtureBytes(name).decodeToString()",
            "",
        ).joinToString("\n")
        output.get().file("com/mcosta/mybooks/testing/Fixtures.kt").asFile.apply { parentFile.mkdirs(); writeText(source) }
    }
}

kotlin {
    android {
        namespace = "com.mcosta.mybooks.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.xmlutil.core)
            implementation(libs.okio)
            implementation(libs.room.runtime)
            implementation(libs.jb.lifecycle.viewmodel)
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(libs.jb.navigation.compose)
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
            implementation(libs.jb.compose.material3)
            implementation(libs.jb.compose.material.icons)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(project.dependencies.platform(libs.supabase.bom))
            implementation(libs.supabase.auth)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.storage)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
        commonTest {
            kotlin.srcDir(generateTestFixtures)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqlite.bundled)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.sqlite.framework)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.play.services.code.scanner)
            implementation(libs.play.services.text.recognition)
            implementation(libs.androidx.exifinterface)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.ktor.client.mock)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    // Robolectric downloads its Android runtime jars itself; on Windows trust the OS
    // certificate store so TLS-inspecting networks work.
    if (System.getProperty("os.name").startsWith("Windows")) {
        systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
    }
}
