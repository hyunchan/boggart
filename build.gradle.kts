import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "io.github.hyunchan.boggart"
    // 릴리스 워크플로는 태그에서 뽑은 버전을 -PVERSION_NAME으로 넘긴다.
    version = providers.gradleProperty("VERSION_NAME").get()
}

private val moduleDescriptions = mapOf(
    "boggart-core" to "Boggart core: rule model, request matching, response rewriting engine and exchange log. Pure JVM.",
    "boggart-okhttp" to "Boggart for OkHttp and Retrofit: an application interceptor.",
    "boggart-ktor" to "Boggart for Ktor: an HttpClientEngine wrapper.",
    "boggart-android" to "Boggart for Android: asset presets, saved rules, rule editor screens and the floating bubble.",
    "boggart-noop" to "No-op Boggart for release builds (releaseImplementation).",
)

// 라이브러리 모듈 공통 발행 설정. 각 모듈은 플러그인만 적용한다.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            // 서명 키가 있을 때만 서명한다. 키 없이도 publishToMavenLocal이 통과해야 한다. CI에서 비밀값이 빠지면 빈 문자열로 온다.
            // Central은 서명 없는 아티팩트를 받지 않으므로, Central로 올릴 때 키가 없으면 업로드 전에 멈춘다.
            val hasSigningKey = !providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()
            val publishesToCentral = gradle.startParameter.taskNames.any { it.contains("MavenCentral") }
            check(hasSigningKey || !publishesToCentral) { "Maven Central에 올리려면 signingInMemoryKey가 필요하다." }
            if (hasSigningKey) {
                signAllPublications()
            }
            coordinates(project.group.toString(), project.name, project.version.toString())
            pom {
                name.set(project.name)
                description.set(moduleDescriptions.getValue(project.name))
                inceptionYear.set("2026")
                url.set("https://github.com/hyunchan/boggart")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("hyunchan")
                        name.set("hyunchan")
                        url.set("https://github.com/hyunchan")
                    }
                }
                scm {
                    url.set("https://github.com/hyunchan/boggart")
                    connection.set("scm:git:git://github.com/hyunchan/boggart.git")
                    developerConnection.set("scm:git:ssh://git@github.com/hyunchan/boggart.git")
                }
            }
        }
    }
}

// JVM 모듈은 JDK 21로 빌드하되 Java 17 바이트코드로 낸다. 안드로이드 모듈(Java 17)과 맞추고,
// JDK 17로 빌드하는 소비자도 의존성을 해석할 수 있게 한다.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }
        extensions.configure<JavaPluginExtension> {
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

// 모든 모듈에 ktlint를 적용한다. 규칙 조정은 루트 .editorconfig에 근거와 함께 둔다.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    val isAndroid = path in setOf(":boggart-android", ":boggart-noop", ":sample")

    extensions.configure<KtlintExtension> {
        android.set(isAndroid)
        outputToConsole.set(true)
        filter {
            // 패턴 exclude는 소스셋 루트 상대경로로 매칭돼 생성 산출물을 걸러내지 못한다.
            exclude { it.file.invariantSeparatorsPath.contains("/build/") }
        }
    }

    // Compose를 쓰는 모듈만 Compose 규칙을 더한다.
    if (path in setOf(":boggart-android", ":sample")) {
        dependencies.add("ktlintRuleset", rootProject.libs.ktlint.compose.rules)
    }
}
