import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Secrets live in the untracked local.properties, e.g. GOOGLE_BOOKS_API_KEY=AIza...
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.mcosta.mybooks"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mcosta.mybooks.ng"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "2.0"

        val googleBooksKey = localProperties.getProperty("GOOGLE_BOOKS_API_KEY", "").trim()
        buildConfigField("String", "GOOGLE_BOOKS_API_KEY", "\"$googleBooksKey\"")
        for (name in listOf("SUPABASE_URL", "SUPABASE_ANON_KEY")) {
            buildConfigField("String", name, "\"${localProperties.getProperty(name, "").trim()}\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Robolectric downloads its Android runtime jars itself; on Windows trust the OS
            // certificate store so TLS-inspecting networks work.
            if (System.getProperty("os.name").startsWith("Windows")) {
                test.systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Screen tests: the main screens drawn by Robolectric and saved as images (build/screenshots).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.okio)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(libs.room.runtime)
    debugImplementation(libs.compose.ui.test.manifest)
}
