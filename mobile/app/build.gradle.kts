plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version de l'application : fixée par la CI de release (titre « Release X.Y.Z »,
// variable KAIROS_VERSION) ; sinon le dernier tag vX.Y.Z ; sinon 0.0.0 (build local sans tag).
val versionPattern = Regex("""^\d+\.\d+\.\d+$""")
val projectVersion: String = System.getenv("KAIROS_VERSION")?.takeIf { versionPattern.matches(it) }
    ?: runCatching {
        providers.exec { commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*") }
            .standardOutput.asText.get().trim().removePrefix("v")
    }.getOrNull()?.takeIf { versionPattern.matches(it) }
    ?: "0.0.0"

// X.Y.Z → X*10000 + Y*100 + Z : toujours croissant d'une version à la suivante (au moins 1).
val projectVersionCode: Int = projectVersion.split('.').map(String::toInt)
    .let { (major, minor, patch) -> maxOf(1, major * 10_000 + minor * 100 + patch) }

// Signature de release lue dans l'environnement (secrets de la CI), jamais dans le dépôt.
val releaseKeystore: String? = System.getenv("KAIROS_KEYSTORE")

android {
    namespace = "app.kairos.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kairos.mobile"
        minSdk = 34
        targetSdk = 36
        versionCode = projectVersionCode
        versionName = projectVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KAIROS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KAIROS_KEY_ALIAS")
                keyPassword = System.getenv("KAIROS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.mlkit)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
    implementation(libs.work.runtime)
    implementation(libs.lifecycle.process)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
