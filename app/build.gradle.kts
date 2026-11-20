plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * versionCode = numărul de commituri din istorie.
 *
 * Crește singur la fiecare commit, deci un APK nou se instalează peste cel vechi
 * (`adb install -r`) fără `INSTALL_FAILED_VERSION_DOWNGRADE`, fără să ținem un
 * contor de mână. CI are nevoie de `fetch-depth: 0`, altfel vede un singur commit.
 */
val gitCommitCount: Provider<Int> = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    workingDir = rootProject.projectDir
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }

/** Keystore-ul de release: local `release.jks` (gitignored) sau decodat în CI. */
val releaseKeystore = rootProject.file("release.jks")
val hasReleaseKeystore = releaseKeystore.exists()

/**
 * Parolele vin din `-Pchecker.storePassword=...` (local) sau din variabile de
 * mediu (CI). Nu se citesc doar din mediu: un GradleDaemon deja pornit nu
 * primește variabilele noi, iar semnarea ar cădea cu „password was incorrect”.
 */
fun signingValue(property: String, env: String): String =
    providers.gradleProperty(property).orNull ?: System.getenv(env).orEmpty()

android {
    namespace = "ro.davinci.checker"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.davinci.checker"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount.get()
        versionName = "1.0.${gitCommitCount.get()}"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("checker.storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = providers.gradleProperty("checker.keyAlias").orNull
                    ?: System.getenv("RELEASE_KEY_ALIAS") ?: "checker"
                keyPassword = signingValue("checker.keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Fără keystore, build-ul rămâne nesemnat (util doar pentru inspecție).
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}