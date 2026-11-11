package pl.sinusoide.sinuschat.link

object LinkProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_sinuschat._tcp"
    const val DEFAULT_PORT = 47321
    const val TXT_ID = "id"
    const val ID_LENGTH = 16
    const val PAIRING_PREFIX = "SCLINK1:"
    const val PAIRING_TTL_MS = 5 * 60_000L
    const val PING_INTERVAL_MS = 5_000L
    const val ACTIVITY_REPEAT_MS = 10_000L
    const val ACTIVITY_TTL_MS = 25_000L
    const val READ_TIMEOUT_MS = 16_000
    const val HANDSHAKE_TIMEOUT_MS = 10_000
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024
}
