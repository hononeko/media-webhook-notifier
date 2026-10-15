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
                "--initialize-at-build-time=redis.clients.jedis.JedisMetaInfo",
                "--enable-http",
                "--enable-https",
                "-H:IncludeResources=schemas/.*\\.json",
                "-H:IncludeResources=templates\\.default\\.yaml",
                "-H:IncludeResources=logback.xml",
                "-H:IncludeResources=redis/clients/jedis/pom\\.properties"
            )
            if (System.getenv("CI") == "true") {
                buildArgs.addAll("-J-Xmx10g", "-J-XX:+UseParallelGC")
            } else {
                buildArgs.add("-J-XX:+UseParallelGC")
            }
            // The binary that goes into the container image, the same on amd64 and arm64:
            // - mostly static: everything linked in except glibc, which the distroless base provides. Fully static
            //   musl binaries can't be built on linux/aarch64 (oracle/graal#5872);
            // - a portable CPU baseline: the defaults (x86-64-v3, armv8.1-a) exclude CPUs common in home servers,
            //   such as Celerons and Atoms without AVX2, and the Raspberry Pi 4 (ARMv8.0).
            if (project.hasProperty("containerImage") || System.getenv("GRAALVM_CONTAINER_IMAGE") == "true") {
                buildArgs.addAll("--static-nolibc", "-march=compatibility")
            }
            if (project.hasProperty("quickBuild") || System.getenv("GRAALVM_QUICK_BUILD") == "true") {
                buildArgs.add("-Ob")
            }
        }
    }
}
