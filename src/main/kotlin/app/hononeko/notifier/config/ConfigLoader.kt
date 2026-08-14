package app.hononeko.notifier.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addEnvironmentSource
import com.sksamuel.hoplite.addResourceSource

object ConfigLoader {
    fun load(): AppConfig =
        ConfigLoaderBuilder
            .default()
            .addEnvironmentSource()
            .addResourceSource("/application.yaml", optional = true)
            .build()
            .loadConfigOrThrow<AppConfig>()
}
