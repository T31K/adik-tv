import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+: Compose compiler is its own plugin. Version tracks Kotlin in
    // the root build.gradle.kts.
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("androidx.baselineprofile")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("io.gitlab.arturbosch.detekt")
    kotlin("plugin.serialization")
}

val includeX86Abis = providers.gradleProperty("includeX86Abis")
    .orNull
    ?.toBooleanStrictOrNull() == true

android {
    namespace = "com.arflix.tv"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    flavorDimensions += "distribution"

    defaultConfig {
        applicationId = "com.adik.tv"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 23
        targetSdk = 36
        versionCode = 20
        versionName = "0.2.5"
        buildConfigField("String", "GITHUB_OWNER", "\"T31K\"")
        buildConfigField("String", "GITHUB_REPO", "\"adik-tv\"")

        // Keep release downloads ARM-universal by default. Developers can add x86
        // emulator support with -PincludeX86Abis=true without changing this file.
        ndk {
            abiFilters += buildList {
                addAll(listOf("armeabi-v7a", "arm64-v8a"))
                if (includeX86Abis) {
                    addAll(listOf("x86", "x86_64"))
                }
            }
        }

        vectorDrawables {
            useSupportLibrary = true
        }

        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }

    // Only the sideload build ships (self-updates from GitHub releases). The
    // flavor dimension is kept so task names (…Sideload…) stay stable.
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "true")
        }
    }

    // Release signing: keystore.properties in the project root with
    // storeFile / storePassword / keyAlias / keyPassword.
    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(keystorePropertiesFile.inputStream())
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use release signing if configured, otherwise fall back to debug
            val releaseSigningConfig = signingConfigs.findByName("release")
            signingConfig = if (releaseSigningConfig?.storeFile != null) {
                releaseSigningConfig
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            isDebuggable = false
            isJniDebuggable = false
            renderscriptOptimLevel = 3

            buildConfigField("Boolean", "ENABLE_CRASH_REPORTING", "true")
        }

        debug {
            isMinifyEnabled = false
            isDebuggable = true
            versionNameSuffix = "-debug"

            buildConfigField("Boolean", "ENABLE_CRASH_REPORTING", "false")
        }

        // Beta build: release-grade optimizations in a separate package.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            val releaseSigningConfig = signingConfigs.findByName("release")
            signingConfig = if (releaseSigningConfig?.storeFile != null) {
                releaseSigningConfig
            } else {
                signingConfigs.getByName("debug")
            }
            isDebuggable = false
            isJniDebuggable = false

            buildConfigField("Boolean", "ENABLE_CRASH_REPORTING", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
        jniLibs {
            // Compressed in the APK and extracted on install: halves the download
            // (libVLC + libtorrent are ~110 MB uncompressed). Still 16 KB page-size
            // safe because the .so files themselves are 16 KB-aligned ELFs.
            useLegacyPackaging = true
            // libVLC ships its own libc++_shared.so; take one copy if another lib does too.
            pickFirsts += "**/libc++_shared.so"
        }
    }

    baselineProfile {
        mergeIntoMain = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Compose stability config marks domain models as stable to avoid unnecessary
// recompositions.
composeCompiler {
    stabilityConfigurationFile = rootProject.layout.projectDirectory
        .file("app/compose_stability_config.conf")
}

// KSP configuration for Hilt
ksp {
    arg("dagger.fastInit", "enabled")
    arg("dagger.formatGeneratedSource", "disabled")
    arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
}

// Kotlin 2.3.0 emits class metadata v2.3.0, which Hilt 2.57's bundled
// kotlin-metadata-jvm (max v2.2.0) cannot read. Force the reader library to
// 2.3.0 on every configuration (incl. Hilt's annotation-processor classpath).
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    }
}

dependencies {
    ksp("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.0")
    annotationProcessor("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.0")

    // Gson explicit pin to keep `JsonParser`/AST extension API stable with current sources.
    implementation("com.google.code.gson:gson:2.10.1")

    // Core library desugaring for Java 8+ APIs
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")  // Android 12+ Splash Screen
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity:1.10.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.activity:activity-compose:1.10.0")

    // Compose BOM — staying on the 2024.06 line keeps tv-foundation 1.0.0-alpha11
    // happy; newer BOMs cause invalid-slot-table crashes on D-pad.
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Compose for TV
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Hilt for DI — 2.57 (last stable for AGP 8.x).
    implementation("com.google.dagger:hilt-android:2.57")
    ksp("com.google.dagger:hilt-compiler:2.57")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // libVLC: the only player. Plays any container/codec off the drive with
    // hardware decoding and automatic software fallback. 3.6.5's arm64 native
    // libs are 16 KB page-aligned (required: jniLibs.useLegacyPackaging=false).
    implementation("org.videolan.android:libvlc-all:3.6.5")

    // Embedded BitTorrent engine (downloads feed items for the USB drive).
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")  // arm64-v8a
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-35")    // armeabi-v7a
    // x86_64 native libs only when building for the emulator (-PincludeX86Abis=true).
    if (includeX86Abis) {
        implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-35")
    }

    // Networking - Retrofit + OkHttp (TMDB + the ADIK feed)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Image loading - Coil
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    implementation("io.coil-kt:coil-svg:2.5.0")
    // QR codes (crash report screen)
    implementation("com.google.zxing:core:3.5.3")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Profile installer for baseline profiles
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // Debug tools
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Firebase Crashlytics - optional, works when google-services.json is present
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-crashlytics-ktx")

    // Sentry crash reporting. Runtime initialization is gated by BuildConfig.ENABLE_CRASH_REPORTING
    // and SENTRY_DSN from secrets.properties/secrets.defaults.properties.
    implementation("io.sentry:sentry-android:8.40.0")

    baselineProfile(project(":benchmark"))

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("app.cash.turbine:turbine:1.0.0")  // Flow testing
    testImplementation("com.google.truth:truth:1.1.5")    // Better assertions
    testImplementation("org.robolectric:robolectric:4.11.1")  // Android mocking
    testImplementation("androidx.test:core-ktx:1.5.0")  // ApplicationProvider for Robolectric

    // Android Instrumented Testing
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

secrets {
    // Secrets file to read from
    propertiesFileName = "secrets.properties"

    // Default file with placeholder values (for CI/new developers)
    defaultPropertiesFileName = "secrets.defaults.properties"

    // Ignore missing keys to allow builds without secrets file
    ignoreList.add("sdk.*")
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/main/java"
        )
    )
    parallel = true
    ignoreFailures = true
}
