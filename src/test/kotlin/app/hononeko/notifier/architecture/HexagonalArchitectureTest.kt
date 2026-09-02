package app.hononeko.notifier.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class HexagonalArchitectureTest {
    @Test
    fun `domain package must not import web frameworks or HTTP client libraries`() {
        val domainDir = File("src/main/kotlin/app/hononeko/notifier/domain")
        assertTrue(domainDir.exists(), "Domain directory must exist")

        val forbiddenImports =
            listOf(
                "io.ktor",
                "io.netty",
                "com.sksamuel.hoplite",
                "org.apache.http",
                "okhttp3"
            )

        val violations = mutableListOf<String>()

        domainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    for (forbidden in forbiddenImports) {
                        if (trimmed.startsWith("import $forbidden")) {
                            violations.add("${file.path}:${index + 1} imports forbidden '$forbidden'")
                        }
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Hexagonal Architecture violation: Domain core must not depend on framework/HTTP libraries:\n" +
                violations.joinToString("\n")
        )
    }

    @Test
    fun `domain package must not import adapter implementations`() {
        val domainDir = File("src/main/kotlin/app/hononeko/notifier/domain")
        assertTrue(domainDir.exists(), "Domain directory must exist")

        val forbidden = "app.hononeko.notifier.adapter"
        val violations = mutableListOf<String>()

        domainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import $forbidden")) {
                    violations.add("${file.path}:${index + 1} imports adapter layer '$trimmed'")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Hexagonal Architecture violation: Domain core must not depend on adapter implementations:\n" +
                violations.joinToString("\n")
        )
    }
}
