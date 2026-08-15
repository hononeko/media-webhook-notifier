package app.hononeko.notifier.adapter.inbound.web.provider

import java.util.concurrent.ConcurrentHashMap

object SchemaLoader {
    private val schemaCache = ConcurrentHashMap<String, String>()

    fun loadSchema(resourcePath: String): String? =
        schemaCache
            .computeIfAbsent(resourcePath) { path ->
                val cleanPath = "/" + path.removePrefix("/")
                SchemaLoader::class.java
                    .getResource(cleanPath)
                    ?.readText()
                    .orEmpty()
            }.takeIf { it.isNotBlank() }
}
