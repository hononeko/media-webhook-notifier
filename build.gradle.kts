plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.ktlint)
    application
}

group = "app.hononeko.notifier"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin & Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Arrow-kt (Declarative Typed Errors)
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)

    // Ktor Server (Lightweight Webhook Ingestor)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    // Ktor Client (qBittorrent & Telegram Bot API)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)

    // Logging & Configuration
    implementation(libs.logback.classic)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.yaml)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
}

application {
    mainClass.set("app.hononeko.notifier.ApplicationKt")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

ktlint {
    version.set("1.5.0")
    verbose.set(true)
    outputToConsole.set(true)
    coloredOutput.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

graalvmNative {

    binaries {
        named("main") {
            imageName.set("media-webhook-notifier")
            mainClass.set("app.hononeko.notifier.ApplicationKt")
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=ch.qos.logback",
                "--enable-http",
                "--enable-https"
            )
        }
    }
}
