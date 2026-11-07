plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// The ebook reader (Android only): Readium for EPUB and PDF, file checks, and the reader screen.
android {
    namespace = "com.mcosta.mybooks.reader"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Readium needs Java 8+ APIs desugared on older Androids.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            if (System.getProperty("os.name").startsWith("Windows")) {
                test.systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.adapter.pdfium)
    implementation(libs.readium.navigator.media.tts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.okio)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
