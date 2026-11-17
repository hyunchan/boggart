plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":boggart-core"))
    api(libs.ktor.client.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
