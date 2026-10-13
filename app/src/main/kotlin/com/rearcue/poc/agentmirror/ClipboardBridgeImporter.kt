package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.BridgeEndpoint
import java.net.URI
import java.security.MessageDigest

/** A clipboard candidate never replaces configuration until identity and credential are verified. */
class ClipboardBridgeImporter(
    private var lastFingerprint: String? = null,
    private val installation: String? = null,
    lastInspectedInstallation: String? = null,
    private val rememberInstallation: (String) -> Unit = {},
    private val rememberFingerprint: (String) -> Unit = {},
) {
    /** Null read result means unavailable; Content(null) is a successfully read empty/non-text clip. */
    data class Content(val raw: String?)
    data class Connection(
        val enabled: Boolean,
        val connected: Boolean,
        val address: String?,
        val revision: Long,
        val configurationLoaded: Boolean = true,
    )
    sealed interface Result {
        data object Ignored : Result
        data object NoLink : Result
        data object Saved : Result
        data class Failed(val probe: BridgeAddressProbe) : Result
    }

    private var generation = 0L
    private var lastClipboardTimestamp: Long? = null
    private var needsInitialInspection = installation != null && installation != lastInspectedInstallation

    fun invalidate() { generation++ }

    suspend fun inspect(
        timestamp: Long?,
        read: () -> Content?,
        connection: () -> Connection,
        verify: suspend (String) -> BridgeAddressProbe,
        save: (String) -> Unit,
        force: Boolean = false,
        focused: () -> Boolean = { true },
    ): Result {
        val before = connection()
        if (!focused() || !before.configurationLoaded || !before.enabled || before.connected) return Result.Ignored
        if (!force && !needsInitialInspection && timestamp != null && timestamp == lastClipboardTimestamp) return Result.Ignored
        if (timestamp != lastClipboardTimestamp) invalidate()
        val content = try { read() } catch (_: SecurityException) { return Result.Ignored }
        if (content == null || !focused() || connection() != before) return Result.Ignored
        lastClipboardTimestamp = timestamp
        return inspect(content.raw, connection, verify, save, force, focused = true)
    }

    suspend fun inspect(
        raw: String?,
        connection: () -> Connection,
        verify: suspend (String) -> BridgeAddressProbe,
        save: (String) -> Unit,
        force: Boolean = false,
        focused: Boolean = true,
    ): Result {
        val before = connection()
        if (!focused || !before.configurationLoaded || !before.enabled || before.connected) return Result.Ignored
        val initialInspection = needsInitialInspection
        if (initialInspection) {
            needsInitialInspection = false
            rememberInstallation(requireNotNull(installation))
        }
        val fingerprint = fingerprint(raw.orEmpty())
        if (!force && !initialInspection && fingerprint == lastFingerprint) return Result.Ignored
        lastFingerprint = fingerprint
        rememberFingerprint(fingerprint)
        val attempt = ++generation
        val candidate = candidate(raw) ?: return Result.NoLink
        val endpoint = BridgeEndpoint.parse(candidate)
        if (!force && !initialInspection && before.address?.let { runCatching { BridgeEndpoint.parse(it) }.getOrNull() } == endpoint) {
            return Result.Ignored
        }
        val probe = verify(candidate)
        // Clipboard changes, manual configuration, disabling, and recovery all supersede old IO.
        if (generation != attempt || connection() != before) return Result.Ignored
        if (probe != BridgeAddressProbe.Reachable) return Result.Failed(probe)
        save(candidate)
        return Result.Saved
    }

    companion object {
        /** Only a complete bridge link is eligible, never an arbitrary copied hostname or prose. */
        fun candidate(raw: String?): String? {
            val text = raw?.trim() ?: return null
            if (!text.startsWith("https://", true) && !text.startsWith("http://", true)) return null
            val normalized = BridgeAddressProbeClient.normalize(text) ?: return null
            return runCatching {
                val uri = URI(normalized)
                val endpoint = BridgeEndpoint.parse(normalized)
                val token = endpoint.accessToken
                if (uri.path.orEmpty() !in listOf("", "/") || uri.query != null || token.isNullOrBlank() ||
                    token.any { it.isWhitespace() || it.isISOControl() }) null else normalized
            }.getOrNull()
        }

        private fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
