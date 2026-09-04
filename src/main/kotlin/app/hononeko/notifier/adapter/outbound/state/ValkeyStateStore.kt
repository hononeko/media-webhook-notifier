package app.hononeko.notifier.adapter.outbound.state

import app.hononeko.notifier.config.StateConfig
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import redis.clients.jedis.ConnectionPoolConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.RedisClient
import redis.clients.jedis.UnifiedJedis
import redis.clients.jedis.params.ScanParams
import redis.clients.jedis.params.SetParams
import redis.clients.jedis.util.JedisURIHelper
import java.net.URI

class ValkeyStateStore(
    private val config: StateConfig,
    private val fallbackStore: StateStorePort = InMemoryStateStore(),
    injectedJedis: UnifiedJedis? = null
) : StateStorePort {
    private val logger = LoggerFactory.getLogger(ValkeyStateStore::class.java)
    private val keyPrefix: String = config.keyPrefix.ifBlank { "mwn:" }
    private val jedis: UnifiedJedis? = injectedJedis ?: initJedis(config)

    @Volatile
    private var isHealthy: Boolean = (jedis != null)

    private fun initJedis(cfg: StateConfig): UnifiedJedis? {
        if (cfg.url.isBlank()) {
            logger.info("Valkey URL is empty; ValkeyStateStore will operate exclusively in-memory fallback mode")
            return null
        }
        return try {
            val normalizedUrl = normalizeUrl(cfg.url)
            val uri = URI(normalizedUrl)
            val poolConfig =
                ConnectionPoolConfig().apply {
                    maxTotal = cfg.maxPoolSize
                }
            val timeout = cfg.timeoutMillis.toInt()
            logger.info(
                "Initializing Valkey/Redis pool at {} with timeout {}ms, maxPool={}",
                uri.host,
                timeout,
                cfg.maxPoolSize
            )
            val clientConfig =
                DefaultJedisClientConfig
                    .builder(uri)
                    .timeoutMillis(timeout)
                    .build()
            val hostAndPort = JedisURIHelper.getHostAndPort(uri)
            RedisClient
                .builder()
                .hostAndPort(hostAndPort)
                .clientConfig(clientConfig)
                .poolConfig(poolConfig)
                .build()
        } catch (e: Exception) {
            logger.error("Failed to initialize Valkey/Redis connection pool for '{}': {}", cfg.url, e.message, e)
            null
        }
    }

    private fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val withScheme =
            when {
                trimmed.startsWith("valkeys://", ignoreCase = true) -> "rediss://" + trimmed.substring(10)
                trimmed.startsWith("valkey://", ignoreCase = true) -> "redis://" + trimmed.substring(9)
                !trimmed.contains("://") -> "redis://$trimmed"
                else -> trimmed
            }
        return try {
            val parsed = URI(withScheme)
            if (parsed.port == -1 && parsed.host != null) {
                val defaultPort = if (parsed.scheme?.equals("rediss", ignoreCase = true) == true) 6380 else 6379
                val userInfoPart = if (parsed.rawUserInfo != null) "${parsed.rawUserInfo}@" else ""
                val hostPart = parsed.host
                val pathPart = parsed.rawPath ?: ""
                val queryPart = if (parsed.rawQuery != null) "?${parsed.rawQuery}" else ""
                "${parsed.scheme}://$userInfoPart$hostPart:$defaultPort$pathPart$queryPart"
            } else {
                withScheme
            }
        } catch (_: Exception) {
            withScheme
        }
    }

    override suspend fun tryAcquire(
        key: String,
        ttlSeconds: Long,
        value: String,
        nowMillis: Long
    ): Boolean =
        withContext(Dispatchers.IO) {
            val client = jedis
            if (client == null) {
                return@withContext fallbackStore.tryAcquire(key, ttlSeconds, value, nowMillis)
            }
            try {
                val namespacedKey = keyPrefix + key
                val params = SetParams.setParams().nx().ex(ttlSeconds)
                val result = client.set(namespacedKey, value, params)
                isHealthy = true
                result == "OK"
            } catch (e: Exception) {
                isHealthy = false
                logger.warn("Valkey tryAcquire failed for key '{}', falling back to in-memory: {}", key, e.message)
                fallbackStore.tryAcquire(key, ttlSeconds, value, nowMillis)
            }
        }

    override suspend fun exists(
        key: String,
        nowMillis: Long
    ): Boolean =
        withContext(Dispatchers.IO) {
            val client = jedis
            if (client == null) {
                return@withContext fallbackStore.exists(key, nowMillis)
            }
            try {
                val namespacedKey = keyPrefix + key
                val existsInValkey = client.exists(namespacedKey)
                isHealthy = true
                existsInValkey || fallbackStore.exists(key, nowMillis)
            } catch (e: Exception) {
                isHealthy = false
                logger.warn("Valkey exists failed for key '{}', falling back to in-memory: {}", key, e.message)
                fallbackStore.exists(key, nowMillis)
            }
        }

    override suspend fun get(
        key: String,
        nowMillis: Long
    ): String? =
        withContext(Dispatchers.IO) {
            val client = jedis
            if (client == null) {
                return@withContext fallbackStore.get(key, nowMillis)
            }
            try {
                val namespacedKey = keyPrefix + key
                val value = client.get(namespacedKey)
                isHealthy = true
                value ?: fallbackStore.get(key, nowMillis)
            } catch (e: Exception) {
                isHealthy = false
                logger.warn("Valkey get failed for key '{}', falling back to in-memory: {}", key, e.message)
                fallbackStore.get(key, nowMillis)
            }
        }

    override suspend fun set(
        key: String,
        value: String,
        ttlSeconds: Long?,
        nowMillis: Long
    ) = withContext(Dispatchers.IO) {
        val client = jedis
        if (client == null) {
            fallbackStore.set(key, value, ttlSeconds, nowMillis)
            return@withContext
        }
        try {
            val namespacedKey = keyPrefix + key
            if (ttlSeconds != null && ttlSeconds > 0) {
                client.set(namespacedKey, value, SetParams.setParams().ex(ttlSeconds))
            } else {
                client.set(namespacedKey, value)
            }
            isHealthy = true
        } catch (e: Exception) {
            isHealthy = false
            logger.warn("Valkey set failed for key '{}', falling back to in-memory: {}", key, e.message)
            fallbackStore.set(key, value, ttlSeconds, nowMillis)
        }
    }

    override suspend fun delete(key: String): Boolean =
        withContext(Dispatchers.IO) {
            val fbDeleted = fallbackStore.delete(key)
            val client = jedis ?: return@withContext fbDeleted
            try {
                val namespacedKey = keyPrefix + key
                val deleted = client.del(namespacedKey) > 0
                isHealthy = true
                deleted || fbDeleted
            } catch (e: Exception) {
                isHealthy = false
                logger.warn("Valkey delete failed for key '{}': {}", key, e.message)
                fbDeleted
            }
        }

    override suspend fun healthCheck(): Boolean =
        withContext(Dispatchers.IO) {
            val client = jedis
            if (client == null) {
                return@withContext false
            }
            try {
                val pong = client.ping()
                isHealthy = pong.equals("PONG", ignoreCase = true)
                isHealthy
            } catch (e: Exception) {
                isHealthy = false
                logger.debug("Valkey healthCheck ping failed: {}", e.message)
                false
            }
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            fallbackStore.clear()
            val client = jedis ?: return@withContext
            try {
                val pattern = "$keyPrefix*"
                val scanParams = ScanParams().match(pattern).count(100)
                var cursor = ScanParams.SCAN_POINTER_START
                do {
                    val scanResult = client.scan(cursor, scanParams)
                    val keys = scanResult.result
                    if (!keys.isNullOrEmpty()) {
                        client.del(*keys.toTypedArray())
                    }
                    cursor = scanResult.cursor
                } while (!scanResult.isCompleteIteration && cursor != ScanParams.SCAN_POINTER_START)
                isHealthy = true
            } catch (e: Exception) {
                isHealthy = false
                logger.warn("Valkey clear failed for pattern '$keyPrefix*': {}", e.message)
            }
        }

    override fun close() {
        try {
            jedis?.close()
        } catch (e: Exception) {
            logger.warn("Error closing Valkey Jedis client: {}", e.message)
        }
    }
}
