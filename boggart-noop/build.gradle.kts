plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.hyunchan.boggart.noop"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    // 호스트가 OkHttp와 Ktor 중 한쪽만 쓸 수 있으므로 둘 다 호스트의 의존을 그대로 쓴다.
    compileOnly(libs.okhttp)
    compileOnly(libs.ktor.client.core)

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit.platform.launcher)
}
