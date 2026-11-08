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

        // From local.properties, or the environment in CI.
        fun secret(name: String) = (localProperties.getProperty(name) ?: System.getenv(name) ?: "").trim()
        val googleBooksKey = secret("GOOGLE_BOOKS_API_KEY")
        buildConfigField("String", "GOOGLE_BOOKS_API_KEY", "\"$googleBooksKey\"")
        for (name in listOf("SUPABASE_URL", "SUPABASE_ANON_KEY")) {
            buildConfigField("String", name, "\"${secret(name)}\"")
        }
    }

    // The upload key for Google Play: from local.properties, or the environment in CI. Without it the release
    // build is unsigned (it still builds, it just can't be uploaded).
    val signing = listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
        .associateWith { (localProperties.getProperty(it) ?: System.getenv(it))?.trim()?.takeIf { v -> v.isNotEmpty() } }
    val release = if (signing.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(signing.getValue("RELEASE_STORE_FILE")!!)
            storePassword = signing.getValue("RELEASE_STORE_PASSWORD")
            keyAlias = signing.getValue("RELEASE_KEY_ALIAS")
            keyPassword = signing.getValue("RELEASE_KEY_PASSWORD")
        }
    } else {
        null
    }

    // Published as "MyBooks". Debug builds keep "MyBooks NG" so they can be told apart on a phone that also has the
    // original MyBooks app installed.
    buildTypes {
        debug {
            resValue("string", "app_name", "MyBooks NG")
        }
        release {
            isMinifyEnabled = false
            signingConfig = release
            resValue("string", "app_name", "MyBooks")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The reader (Readium) needs Java 8+ APIs desugared on older Androids.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
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
    implementation(project(":androidReader"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.work.runtime)
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
    // A whole-app test (BookNerdConversationTest) answers every request offline.
    testImplementation(libs.ktor.client.mock)
    debugImplementation(libs.compose.ui.test.manifest)
}
