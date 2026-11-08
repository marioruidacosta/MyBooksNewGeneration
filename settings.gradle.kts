pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Readium's PDF engine (PdfiumAndroid, AndroidPdfViewer) is published only on JitPack, as Readium's setup says.
        // Limited to that one group, so nothing else can come from JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.marain87") }
        }
    }
}
rootProject.name = "MyBooks"
include(":androidApp", ":shared", ":releases", ":androidReader")
