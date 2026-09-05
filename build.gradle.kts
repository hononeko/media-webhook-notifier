plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.ktlint)
    application
    jacoco
}

group = "app.hononeko.notifier"
version = "1.0.1"

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
    implementation(libs.ktor.server.forwarded.header)

    // Ktor Client (qBittorrent & Telegram Bot API)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)

    // Logging & Configuration
    implementation(libs.logback.classic)

    // State Store (Valkey / Redis)
    implementation(libs.jedis)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
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
    useJUnitPlatform {
        excludeTags("integration")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
    finalizedBy(tasks.jacocoTestReport)
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests using Testcontainers."
        group = "verification"
        val testSourceSet = sourceSets["test"]
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform {
            includeTags("integration")
        }
        testLogging {
            events("passed", "skipped", "failed")
        }
        shouldRunAfter(tasks.test)
    }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
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
                "--enable-https",
                "-H:IncludeResources=schemas/.*\\.json",
                "-H:IncludeResources=templates\\.default\\.yaml",
                "-H:IncludeResources=logback.xml"
            )
            if (System.getenv("CI") == "true") {
                buildArgs.addAll("-J-Xmx10g", "-J-XX:+UseParallelGC")
            } else {
                buildArgs.add("-J-XX:+UseParallelGC")
            }
            if (project.hasProperty("static") || System.getenv("GRAALVM_STATIC") == "true") {
                buildArgs.addAll("--static", "--libc=musl")
            }
            if (project.hasProperty("quickBuild") || System.getenv("GRAALVM_QUICK_BUILD") == "true") {
                buildArgs.add("-Ob")
            }
        }
    }
}
