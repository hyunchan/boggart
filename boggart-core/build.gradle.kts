plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.serialization)
}

// Boggart.VERSION을 발행 버전(VERSION_NAME, 릴리스는 태그 버전)에서 만든다. 손으로 고치다 낡는 일을 막는다.
val generateVersion by tasks.registering {
    val version = providers.gradleProperty("VERSION_NAME")
    val outputDir = layout.buildDirectory.dir("generated/boggartVersion")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("io/github/hyunchan/boggart/BoggartVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package io.github.hyunchan.boggart\n\ninternal const val BOGGART_VERSION: String = \"${version.get()}\"\n")
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    sourceSets.main { kotlin.srcDir(generateVersion) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
