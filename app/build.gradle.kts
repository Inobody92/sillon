plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

// Identifiants de signature : jamais dans le dépôt. On cherche d'abord à la racine du projet, puis dans
// ~/.sillon-signing/ (voir README). Sans eux, la variante release est construite non signée.
val signingProps = Properties().apply {
    listOf(
        rootProject.file("keystore.properties"),
        File(System.getProperty("user.home"), ".sillon-signing/keystore.properties"),
    ).firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}

// Permet d'installer une variante de test à côté de l'application principale : -PappIdSuffix=.test
val appIdSuffix = providers.gradleProperty("appIdSuffix").getOrElse("")

android {
    namespace = "io.github.inobody92.sillon"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.inobody92.sillon$appIdSuffix"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.4.1"
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Code réduit et obfusqué, ressources inutilisées retirées : APK plus léger et plus rapide.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Les classes Android (Log, Uri…) renvoient des valeurs neutres dans les tests exécutés sur l'ordinateur.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.reorderable)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // org.json réel : celui d'Android n'est qu'une coquille vide dans les tests sur ordinateur.
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
