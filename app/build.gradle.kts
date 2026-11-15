import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

val signingPropertiesFile = layout.projectDirectory.file("signing/signing.local.properties").asFile
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.minavigatering.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.minavigatering.probe"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingProperties.isNotEmpty()) {
            create("probeDebug") {
                storeFile = layout.projectDirectory.file("signing/debug.jks").asFile
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (signingProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("probeDebug")
            }
        }
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
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(files("libs/xms-wearable-lib_1.4_release.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}

// Generate the local debug keystore when it is missing; it is intentionally untracked.
val generateDebugSigning = tasks.register<Exec>("generateDebugSigning") {
    val script = rootProject.layout.projectDirectory.file("scripts/gen-debug-signing.py").asFile
    val keystore = layout.projectDirectory.file("signing/debug.jks").asFile
    onlyIf { !keystore.exists() }
    workingDir = rootProject.projectDir
    commandLine("python", script.absolutePath)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateDebugSigning)
}
