package app.hononeko.notifier.domain.port.outbound

/** Outcome of renewing a lock. Only [LOST] means another owner holds it now. */
enum class LockRenewal {
    RENEWED,
    LOST,

    /** The store couldn't be asked (e.g. Valkey unreachable); the caller should assume it still holds the lock. */
    STORE_UNAVAILABLE
}
