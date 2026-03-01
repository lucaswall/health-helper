plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sentry)
}

android {
    namespace = "com.healthhelper.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.healthhelper.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM manages versions)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.extended)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Logging
    implementation(libs.timber)

    // Health Connect
    implementation(libs.health.connect)

    // Ktor HTTP client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // DataStore
    implementation(libs.datastore.preferences)

    // Encrypted SharedPreferences
    implementation(libs.security.crypto)

    // Sentry
    implementation(libs.sentry.android)
    implementation(libs.sentry.compose.android)
    implementation(libs.sentry.android.timber)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.junit5.params)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.client.mock)
}

sentry {
    autoInstallation.enabled.set(false)
    // ProGuard mapping upload — only useful for minified (release) builds
    autoUploadProguardMapping.set(false)
    // Source context — disabled to keep builds fast; source is readable in debug stack traces
    autoUploadSourceContext.set(false)
    // Bytecode instrumentation — auto-instruments HTTP/DB for perf traces but is CPU-heavy at build time.
    // Crash reporting, ANRs, breadcrumbs, and session tracking all work without this.
    tracingInstrumentation.enabled.set(false)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
