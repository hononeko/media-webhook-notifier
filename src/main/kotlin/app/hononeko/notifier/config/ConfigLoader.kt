package app.hononeko.notifier.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addEnvironmentSource
import com.sksamuel.hoplite.addResourceSource

object ConfigLoader {
    fun load(): AppConfig =
        ConfigLoaderBuilder
            .default()
            .addResourceSource("/application.yaml", optional = true)
            .addEnvironmentSource()
            .build()
            .loadConfigOrThrow<AppConfig>()
}
