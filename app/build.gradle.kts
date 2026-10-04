plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rearcue.poc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rearcue.poc"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // 单测读 BuildConfig.APPLICATION_ID 校验 manifest 里的 Shizuku authority（票 #8）
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":agent"))
    implementation(project(":notification"))
    implementation(project(":rear"))
    // Manifest 注册的 ShizukuProvider 要在 app 编译/检查面可见（:rear 的 implementation 不传递）。
    implementation(libs.shizuku.provider)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commons.compress)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(kotlin("test"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test>().configureEach {
    // Android instrumentation tests stay on JUnit 4; only local JVM tests use JUnit 5.
    if (!name.contains("AndroidTest", ignoreCase = true)) {
        useJUnitPlatform()
    }
}
