import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mtgscanbuild"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mtgscanbuild"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PRODUCTION_ADS_ENABLED", "false")
        buildConfigField("boolean", "INTERNAL_PRO_CHECKOUT", "false")
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["admobAppId"] = "ca-app-pub-6488482599060029~3946624994"
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"ca-app-pub-6488482599060029/8235197674\"")
        }
        create("internalTesting") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            versionNameSuffix = "-internal"
            buildConfigField("boolean", "INTERNAL_PRO_CHECKOUT", "true")
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "DEVELOPER_UNLOCK", "false")
        }
        create("developer") {
            dimension = "distribution"
            applicationIdSuffix = ".developer"
            versionNameSuffix = "-developer"
            buildConfigField("boolean", "DEVELOPER_UNLOCK", "true")
            resValue("string", "app_name", "MTG Scan Build DEV")
            signingConfig = signingConfigs.getByName("debug")
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
}

val developerBundleTasks = android.buildTypes.map {
    "bundleDeveloper${it.name.replaceFirstChar { letter -> letter.uppercaseChar() }}"
}.toSet()

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name in developerBundleTasks }) {
        throw GradleException("The unlocked developer variant must not be distributed on Google Play. Build bundlePlayRelease instead.")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.matching { it.name.startsWith("processDeveloper") && it.name.endsWith("GoogleServices") }.configureEach {
    enabled = false
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    "playImplementation"(platform("com.google.firebase:firebase-bom:34.19.0"))
    "playImplementation"("com.google.firebase:firebase-auth")
    "playImplementation"("com.google.firebase:firebase-firestore")
    "playImplementation"("com.google.firebase:firebase-functions")
    "playImplementation"("com.google.firebase:firebase-appcheck-playintegrity")
    "playImplementation"("androidx.credentials:credentials:1.6.0")
    "playImplementation"("androidx.credentials:credentials-play-services-auth:1.6.0")
    "playImplementation"("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    "playImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    // Firestore's runtime Guava replaces CameraX's standalone ListenableFuture artifact.
    "playImplementation"("com.google.guava:guava:33.6.0-android")
    "playImplementation"("com.google.android.gms:play-services-ads:25.5.0")
    "playImplementation"("com.google.android.ump:user-messaging-platform:4.0.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.navigation:navigation-compose:2.9.3")

    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    val room = "2.7.2"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.android.billingclient:billing:9.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
