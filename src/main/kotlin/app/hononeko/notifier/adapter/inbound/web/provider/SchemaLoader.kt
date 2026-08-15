package app.hononeko.notifier.adapter.inbound.web.provider

import java.util.concurrent.ConcurrentHashMap

object SchemaLoader {
    private val schemaCache = ConcurrentHashMap<String, String>()

    fun loadSchema(resourcePath: String): String? =
        schemaCache
            .computeIfAbsent(resourcePath) { path ->
                val cleanPath = path.removePrefix("/")
                val stream =
                    Thread.currentThread().contextClassLoader.getResourceAsStream(cleanPath)
                        ?: SchemaLoader::class.java.classLoader.getResourceAsStream(cleanPath)
                if (stream == null) {
                    ""
                } else {
                    stream.bufferedReader().use { it.readText() }
                }
            }.takeIf { it.isNotBlank() }
}
