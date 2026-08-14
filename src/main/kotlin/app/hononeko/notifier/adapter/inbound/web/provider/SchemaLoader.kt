package app.hononeko.notifier.adapter.inbound.web.provider

object SchemaLoader {
    private val schemaCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun loadSchema(resourcePath: String): String? =
        schemaCache
            .computeIfAbsent(resourcePath) { path ->
                val cleanPath = path.removePrefix("/")
                val stream =
                    Thread.currentThread().contextClassLoader.getResourceAsStream(cleanPath)
                        ?: SchemaLoader::class.java.classLoader.getResourceAsStream(cleanPath)
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            }.takeIf { it.isNotBlank() }
}
