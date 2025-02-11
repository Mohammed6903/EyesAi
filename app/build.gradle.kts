import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.room)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val localProps = Properties()
val localPropertiesFile = File(rootDir,"ai.properties")
if (localPropertiesFile.exists() && localPropertiesFile.isFile){
    localPropertiesFile.inputStream().use {
        localProps.load(it)
    }
}


android {
    namespace = "com.example.eyesai"
    compileSdk = 35

    room {
        schemaDirectory("$projectDir/schemas")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = "com.example.eyesai"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "GEMINI_API_KEY", localProps.getProperty("API_KEY"))
        }
        debug {
            buildConfigField("String", "GEMINI_API_KEY", localProps.getProperty("API_KEY"))
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
        buildConfig = true
        mlModelBinding = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.hilt.android)
    // The following line is optional, as the core library is included indirectly by camera-camera2
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    // If you want to additionally use the CameraX Lifecycle library
    implementation(libs.androidx.camera.lifecycle)
    // If you want to additionally use the CameraX VideoCapture library
    implementation(libs.androidx.camera.video)
    // If you want to additionally use the CameraX View class
    implementation(libs.androidx.camera.view)
    // If you want to additionally add CameraX ML Kit Vision Integration
    implementation(libs.androidx.camera.mlkit.vision)
    // If you want to additionally use the CameraX Extensions library
    implementation(libs.androidx.camera.extensions)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.gson)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.benchmark.common)
    implementation(libs.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.datastore.preferences)

    // optional - RxJava2 support
    implementation(libs.androidx.datastore.preferences.rxjava2)

    // optional - RxJava3 support
    implementation(libs.androidx.datastore.preferences.rxjava3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.dagger)

    implementation(libs.androidx.room.runtime)

    implementation(libs.coil.compose)
    implementation(libs.androidx.media)

    implementation(libs.androidx.foundation)

    // If this project uses any Kotlin source, use Kotlin Symbol Processing (KSP)
    // See Add the KSP plugin to your project
//    implementation(kotlin("stdlib-jdk8"))

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)

    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.android)

    // optional - Kotlin Extensions and Coroutines support for Room
    implementation(libs.androidx.room.ktx)

    // optional - RxJava2 support for Room
    implementation(libs.androidx.room.rxjava2)

    // optional - RxJava3 support for Room
    implementation(libs.androidx.room.rxjava3)

    // optional - Guava support for Room, including Optional and ListenableFuture
    implementation(libs.androidx.room.guava)

    // optional - Test helpers
    testImplementation(libs.androidx.room.testing)

    // optional - Paging 3 Integration
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(kotlin("script-runtime"))

    // shared preference
    implementation(libs.androidx.preference.ktx)

    // BarCode Scanner
    implementation(libs.barcode.scanning)

    // OCR
    implementation(libs.text.recognition)

    // Levenshtein Distance
    implementation(libs.commons.text)

    // Face Detection
    implementation(libs.face.detection)
    implementation(libs.androidx.appcompat)

    // Object detection
    implementation("com.google.mlkit:object-detection:17.0.2")

    // Tensorflow Lite
    implementation(libs.tensorflow.lite.task.vision)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite)

    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.3")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.9.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.0")

    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    implementation(libs.generativeai)
    implementation(kotlin("script-runtime"))
}