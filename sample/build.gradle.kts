import java.util.zip.ZipInputStream

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

// release APK에 no-op 외의 Boggart 코드가 섞이지 않았는지 확인한다. noop AAR에 있는 클래스만 허용한다.
val checkReleaseIsolation = tasks.register("checkReleaseIsolation") {
    group = "verification"
    description = "Fails if the release APK contains Boggart classes other than boggart-noop."
    dependsOn("assembleRelease", ":boggart-noop:bundleReleaseAar")
    val boggartPackage = "io/github/hyunchan/boggart/"
    val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")
    val noopAar = rootProject.layout.projectDirectory.file("boggart-noop/build/outputs/aar/boggart-noop-release.aar")
    inputs.dir(releaseApkDir)
    inputs.file(noopAar)
    doLast {
        val descriptor = Regex("L(${Regex.escape(boggartPackage)}[A-Za-z0-9_/$]+);")
        // 샘플 앱 자신의 코드와 noop 모듈의 리소스 클래스(R)는 Boggart 구현이 아니다.
        val notBoggart = listOf("${boggartPackage}sample/", "${boggartPackage}noop/")

        fun zipEntries(bytes: ByteArray): Sequence<Pair<String, ByteArray>> = sequence {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    yield(entry.name to zip.readBytes())
                }
            }
        }

        val noopJar = zipEntries(noopAar.asFile.readBytes()).first { it.first == "classes.jar" }.second
        val allowed = zipEntries(noopJar).map { it.first }
            .filter { it.startsWith(boggartPackage) && it.endsWith(".class") }
            .map { it.removeSuffix(".class") }
            .toSet()

        val apk = releaseApkDir.get().asFile.walk().single { it.extension == "apk" }
        val found = zipEntries(apk.readBytes())
            .filter { it.first.matches(Regex("classes\\d*\\.dex")) }
            .flatMap { descriptor.findAll(String(it.second, Charsets.ISO_8859_1)).map { match -> match.groupValues[1] } }
            .filterNot { name -> notBoggart.any(name::startsWith) }
            .toSortedSet()

        check(found.isNotEmpty()) { "No Boggart classes found in ${apk.name}; the check can't see the no-op either." }
        // D8이 람다 등을 위해 만든 합성 클래스는 그 클래스를 낳은 원래 클래스로 판정한다.
        val leaked = found.filterNot { it.substringBefore("$$") in allowed }
        check(leaked.isEmpty()) { "Release APK contains Boggart classes outside boggart-noop:\n" + leaked.joinToString("\n") }
        logger.lifecycle("Release isolation: ${found.size} Boggart classes, all from boggart-noop.")
    }
}

tasks.named("check") { dependsOn(checkReleaseIsolation) }
