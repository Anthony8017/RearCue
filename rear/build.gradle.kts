plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rearcue.poc.rear"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }

    buildFeatures {
        // Shizuku UserService 走 AIDL 接口（IRearShell）；背屏 Dashboard 用 Compose
        aidl = true
        compose = true
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
    implementation(libs.kotlinx.coroutines.core)

    // Shizuku：经 shell uid 投送背屏（ADR-0001 Route A）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // 背屏 Dashboard（RearDashboardActivity）
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
