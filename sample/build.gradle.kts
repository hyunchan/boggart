plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

private val sampleCompileSdk = 37

android {
    namespace = "io.github.hyunchan.boggart.sample"
    // Ktor OkHttp 엔진 3.6.0이 OkHttp 5.5.0(compileSdk 37 요구)에 의존한다. 라이브러리는 36을 유지하고 샘플만 올린다.
    compileSdk = sampleCompileSdk

    defaultConfig {
        applicationId = "io.github.hyunchan.boggart.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = sampleCompileSdk
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
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
}

dependencies {
    // 호스트 앱이 쓰는 방식 그대로: debug는 실제 구현, release는 no-op. 소스는 같다.
    debugImplementation(project(":boggart-android"))
    debugImplementation(project(":boggart-okhttp"))
    debugImplementation(project(":boggart-ktor"))
    releaseImplementation(project(":boggart-noop"))

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
