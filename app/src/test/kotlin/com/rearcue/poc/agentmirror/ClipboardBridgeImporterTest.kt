package com.rearcue.poc.agentmirror

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardBridgeImporterTest {
    private val link = "https://new.test/#token=valid"
    private val old = "https://old.test/#token=old"

    @Test fun `unavailable clipboard read does not suppress a later valid read of the same timestamp`() = runBlocking {
        for ((failure, installation) in listOf("null" to null, "security" to null,
            "null" to "100:200", "security" to "100:200")) {
            val importer = ClipboardBridgeImporter(installation = installation)
            var address = old
            var probes = 0
            val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
            val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.Reachable }
            assertEquals(ClipboardBridgeImporter.Result.Ignored, importer.inspect(timestamp = 100, read = {
                if (failure == "security") throw SecurityException("focus revoked") else null
            }, connection, verify, { address = it }))
            assertEquals(0, probes)
            assertEquals(old, address)
            assertEquals(ClipboardBridgeImporter.Result.Saved,
                importer.inspect(timestamp = 100, read = { ClipboardBridgeImporter.Content(link) },
                    connection, verify, { address = it }))
            assertEquals(link, address)
            assertEquals(1, probes)
        }
    }

    @Test fun `focus lost during reading keeps the same timestamp eligible after focus returns`() = runBlocking {
        val importer = ClipboardBridgeImporter()
        var address = old
        var probes = 0
        var focused = true
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
        val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.Reachable }
        assertEquals(ClipboardBridgeImporter.Result.Ignored, importer.inspect(timestamp = 100, read = {
            focused = false
            ClipboardBridgeImporter.Content(link)
        }, connection, verify, { address = it }, focused = { focused }))
        assertEquals(0, probes)
        assertEquals(old, address)
        focused = true
        assertEquals(ClipboardBridgeImporter.Result.Saved,
            importer.inspect(timestamp = 100, read = { ClipboardBridgeImporter.Content(link) },
                connection, verify, { address = it }, focused = { focused }))
        assertEquals(link, address)
        assertEquals(1, probes)
    }

    @Test fun `observing a changed timestamp supersedes older verification even when new read is unavailable`() = runBlocking {
        val importer = ClipboardBridgeImporter(installation = "100:200")
        var address = old
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val older = launch {
            assertEquals(ClipboardBridgeImporter.Result.Ignored,
                importer.inspect(timestamp = 100, read = { ClipboardBridgeImporter.Content(link) }, connection, {
                    entered.complete(Unit); release.await(); BridgeAddressProbe.Reachable
                }, { address = it }))
        }
        entered.await()
        assertEquals(ClipboardBridgeImporter.Result.Ignored,
            importer.inspect(timestamp = 200, read = { null }, connection,
                { error("unavailable clipboard must not probe") }, { address = it }))
        release.complete(Unit)
        older.join()
        assertEquals(old, address)
    }

    @Test fun `successfully read empty or non-text content invalidates old verification and stays deduplicated`() = runBlocking {
        val importer = ClipboardBridgeImporter(installation = "100:200")
        var address = old
        var probes = 0
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val older = launch {
            assertEquals(ClipboardBridgeImporter.Result.Ignored,
                importer.inspect(timestamp = 100, read = { ClipboardBridgeImporter.Content(link) }, connection, {
                    probes++; entered.complete(Unit); release.await(); BridgeAddressProbe.Reachable
                }, { address = it }))
        }
        entered.await()
        assertEquals(ClipboardBridgeImporter.Result.NoLink,
            importer.inspect(timestamp = 200, read = { ClipboardBridgeImporter.Content(null) }, connection,
                { error("empty content must not probe") }, { address = it }))
        assertEquals(ClipboardBridgeImporter.Result.Ignored,
            importer.inspect(timestamp = 200, read = { error("successfully consumed timestamp must not be reread") },
                connection, { error("must not probe") }, { address = it }))
        release.complete(Unit)
        older.join()
        assertEquals(old, address)
        assertEquals(1, probes)
    }

    @Test fun `clipboard is not read before eligibility and first failed verification does not loop`() = runBlocking {
        val eligible = ClipboardBridgeImporter.Connection(true, false, old, 0)
        for ((initialState, initialFocus) in listOf(eligible.copy(enabled = false) to true,
            eligible.copy(connected = true) to true, eligible.copy(configurationLoaded = false) to true,
            eligible to false)) {
            val importer = ClipboardBridgeImporter(installation = "100:200")
            var state = initialState
            var focused = initialFocus
            var address = old
            var probes = 0
            val connection = { state }
            val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.HttpStatus(401) }
            assertEquals(ClipboardBridgeImporter.Result.Ignored, importer.inspect(timestamp = 100,
                read = { error("ineligible clipboard must not be read") }, connection, verify, { address = it },
                focused = { focused }))
            state = eligible
            focused = true
            assertEquals(ClipboardBridgeImporter.Result.Failed(BridgeAddressProbe.HttpStatus(401)),
                importer.inspect(timestamp = 100, read = { ClipboardBridgeImporter.Content(link) },
                    connection, verify, { address = it }, focused = { focused }))
            repeat(3) {
                assertEquals(ClipboardBridgeImporter.Result.Ignored, importer.inspect(timestamp = 100,
                    read = { error("successful read must not repeat after failure") }, connection, verify,
                    { address = it }, focused = { focused }))
            }
            assertEquals(old, address)
            assertEquals(1, probes)
        }
    }

    @Test fun `reinstalled app checks restored clipboard once even when it previously failed`() = runBlocking {
        var address = old
        var probes = 0
        var fingerprint: String? = null
        var checkedInstallation: String? = "100:100"
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
        val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.HttpStatus(401) }
        ClipboardBridgeImporter(rememberFingerprint = { fingerprint = it })
            .inspect(link, connection, verify, { address = it })

        val reinstalled = ClipboardBridgeImporter(
            lastFingerprint = fingerprint,
            installation = "100:200",
            lastInspectedInstallation = checkedInstallation,
            rememberInstallation = { checkedInstallation = it },
            rememberFingerprint = { fingerprint = it },
        )
        assertEquals(ClipboardBridgeImporter.Result.Failed(BridgeAddressProbe.HttpStatus(401)),
            reinstalled.inspect(link, connection, verify, { address = it }))
        repeat(3) {
            assertEquals(ClipboardBridgeImporter.Result.Ignored,
                reinstalled.inspect(link, connection, verify, { address = it }))
        }
        val reopened = ClipboardBridgeImporter(
            lastFingerprint = fingerprint,
            installation = "100:200",
            lastInspectedInstallation = checkedInstallation,
        )
        assertEquals(ClipboardBridgeImporter.Result.Ignored,
            reopened.inspect(link, connection, verify, { address = it }))
        assertEquals(old, address)
        assertEquals(2, probes)
    }
    @Test fun `first check of this installation verifies and saves an already configured address`() = runBlocking {
        var address = link
        var probes = 0
        var saves = 0
        val importer = ClipboardBridgeImporter(installation = "100:200", lastInspectedInstallation = "100:100")
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
        val result = importer.inspect(link, connection,
            { probes++; BridgeAddressProbe.Reachable }, { address = it; saves++ })
        assertEquals(ClipboardBridgeImporter.Result.Saved, result)
        assertEquals(link, address)
        assertEquals(1, probes)
        assertEquals(1, saves)
    }
    @Test fun `first check waits for loaded configuration and foreground focus`() = runBlocking {
        var address = old
        var probes = 0
        var state = ClipboardBridgeImporter.Connection(true, false, address, 0, configurationLoaded = false)
        val importer = ClipboardBridgeImporter(installation = "100:200")
        val connection = { state }
        val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.Reachable }
        assertEquals(ClipboardBridgeImporter.Result.Ignored,
            importer.inspect(link, connection, verify, { address = it }, focused = true))
        state = state.copy(configurationLoaded = true)
        assertEquals(ClipboardBridgeImporter.Result.Ignored,
            importer.inspect(link, connection, verify, { address = it }, focused = false))
        assertEquals(0, probes)
        assertEquals(old, address)
        assertEquals(ClipboardBridgeImporter.Result.Saved,
            importer.inspect(link, connection, verify, { address = it }, focused = true))
        assertEquals(link, address)
        assertEquals(1, probes)
    }

    @Test fun `each APK installation gets one check while ordinary app reopening stays deduplicated`() = runBlocking {
        val directory = Files.createTempDirectory("rearcue-clipboard-installation").toFile()
        val contentRecord = directory.resolve("content-fingerprint")
        val installationRecord = directory.resolve("installation")
        var address = old
        var probes = 0
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
        val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.HttpStatus(401) }
        fun app(installation: String) = ClipboardBridgeImporter(
            lastFingerprint = contentRecord.takeIf { it.exists() }?.readText(),
            installation = installation,
            lastInspectedInstallation = installationRecord.takeIf { it.exists() }?.readText(),
            rememberInstallation = { installationRecord.writeText(it) },
            rememberFingerprint = { contentRecord.writeText(it) },
        )
        try {
            assertEquals(ClipboardBridgeImporter.Result.Failed(BridgeAddressProbe.HttpStatus(401)),
                app("100:100").inspect(link, connection, verify, { address = it }))
            repeat(3) {
                assertEquals(ClipboardBridgeImporter.Result.Ignored,
                    app("100:100").inspect(link, connection, verify, { address = it }))
            }
            // Replacement install keeps firstInstallTime but advances lastUpdateTime.
            assertEquals(ClipboardBridgeImporter.Result.Failed(BridgeAddressProbe.HttpStatus(401)),
                app("100:200").inspect(link, connection, verify, { address = it }))
            assertEquals(ClipboardBridgeImporter.Result.Ignored,
                app("100:200").inspect(link, connection, verify, { address = it }))
            // Restore can bring content dedup back, but never the no-backup installation record.
            installationRecord.delete()
            assertEquals(ClipboardBridgeImporter.Result.Failed(BridgeAddressProbe.HttpStatus(401)),
                app("300:300").inspect(link, connection, verify, { address = it }))
            assertEquals(ClipboardBridgeImporter.Result.Ignored,
                app("300:300").inspect(link, connection, verify, { address = it }))
            assertEquals(old, address)
            assertEquals(3, probes)
        } finally {
            contentRecord.delete()
            installationRecord.delete()
            directory.delete()
        }
    }

    @Test fun `first check still filters format and consumes its single content check without looping`() = runBlocking {
        for (raw in listOf(null, "plain text", "new.test", "https://new.test", "copied text $link",
            "https://user@new.test/#token=x", "https://new.test/page#token=x", "https://new.test/#token=",
            "https://new.test/#token=x%0Ay", "https://new.test/?x=y#token=x")) {
            var address = link
            var probes = 0
            var fingerprint: String? = null
            var checkedInstallation: String? = null
            val importer = ClipboardBridgeImporter(installation = "100:200",
                rememberInstallation = { checkedInstallation = it }, rememberFingerprint = { fingerprint = it })
            val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
            val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.Reachable }
            assertEquals(ClipboardBridgeImporter.Result.NoLink,
                importer.inspect(raw, connection, verify, { address = it }))
            repeat(2) {
                assertEquals(ClipboardBridgeImporter.Result.Ignored,
                    importer.inspect(raw, connection, verify, { address = it }))
            }
            val reopened = ClipboardBridgeImporter(lastFingerprint = fingerprint, installation = "100:200",
                lastInspectedInstallation = checkedInstallation)
            // A page/focus/restart cannot replenish the consumed first check; same address stays ignored.
            assertEquals(ClipboardBridgeImporter.Result.Ignored,
                reopened.inspect(link, connection, verify, { address = it }))
            assertEquals(link, address)
            assertEquals(0, probes)
        }
    }

    @Test fun `first and manual check cannot bypass disabled or connected state`() = runBlocking {
        for (state in listOf(ClipboardBridgeImporter.Connection(false, false, old, 0),
            ClipboardBridgeImporter.Connection(true, true, old, 0))) {
            var address = old
            var probes = 0
            val importer = ClipboardBridgeImporter(installation = "100:200")
            val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.Reachable }
            assertEquals(ClipboardBridgeImporter.Result.Ignored,
                importer.inspect(link, { state }, verify, { address = it }))
            assertEquals(ClipboardBridgeImporter.Result.Ignored,
                importer.inspect(link, { state }, verify, { address = it }, force = true))
            assertEquals(0, probes)
            assertEquals(old, address)
            val eligible = state.copy(enabled = true, connected = false)
            assertEquals(ClipboardBridgeImporter.Result.Saved,
                importer.inspect(link, { eligible }, verify, { address = it }))
            assertEquals(link, address)
            assertEquals(1, probes)
        }
    }

    @Test fun `new facts supersede an in-flight first installation check`() = runBlocking {
        for (change in listOf("clipboard", "manual", "disable", "recovery", "invalidate")) {
            val importer = ClipboardBridgeImporter(installation = "100:200")
            var state = ClipboardBridgeImporter.Connection(true, false, old, 0)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var address = old
            val job = launch {
                assertEquals(ClipboardBridgeImporter.Result.Ignored, importer.inspect(link, { state }, {
                    entered.complete(Unit); release.await(); BridgeAddressProbe.Reachable
                }, { address = it }))
            }
            entered.await()
            when (change) {
                "clipboard" -> importer.inspect("unrelated copied text", { state },
                    { error("format must be filtered") }, { address = it })
                "manual" -> state = state.copy(address = "https://manual.test/#token=m", revision = 1)
                "disable" -> state = state.copy(enabled = false)
                "recovery" -> state = state.copy(connected = true)
                else -> importer.invalidate()
            }
            release.complete(Unit)
            job.join()
            assertEquals(old, address, change)
        }
    }
    @Test fun `only complete links qualify`() {
        assertEquals(link, ClipboardBridgeImporter.candidate("  $link  "))
        listOf("new.test", "https://new.test", "copied text $link", "https://new.test/page#token=x",
            "https://new.test/#token=", "https://new.test/#token=x%0Ay", "https://new.test/?x=y#token=x")
            .forEach { assertNull(ClipboardBridgeImporter.candidate(it)) }
    }

    @Test fun `failure preserves configuration and same content is not retried even after restart`() = runBlocking {
        var address = old
        var probes = 0
        var fingerprint: String? = null
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
        val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.HttpStatus(401) }
        val first = ClipboardBridgeImporter(rememberFingerprint = { fingerprint = it })
        assertEquals(ClipboardBridgeImporter.Result.Failed(BridgeAddressProbe.HttpStatus(401)), first.inspect(link, connection, verify, { address = it }))
        assertEquals(ClipboardBridgeImporter.Result.Ignored, first.inspect(link, connection, verify, { address = it }))
        assertEquals(ClipboardBridgeImporter.Result.Ignored, ClipboardBridgeImporter(fingerprint).inspect(link, connection, verify, { address = it }))
        assertEquals(old, address)
        assertEquals(1, probes)
        first.inspect(link, connection, verify, { address = it }, force = true)
        assertEquals(2, probes)
    }

    @Test fun `connected or manually disabled never probes or enables`() = runBlocking {
        var probes = 0
        val importer = ClipboardBridgeImporter()
        val verify: suspend (String) -> BridgeAddressProbe = { probes++; BridgeAddressProbe.Reachable }
        for (state in listOf(ClipboardBridgeImporter.Connection(false, false, old, 0), ClipboardBridgeImporter.Connection(true, true, old, 0))) {
            assertEquals(ClipboardBridgeImporter.Result.Ignored, importer.inspect(link, { state }, verify, { error("must not save") }))
        }
        assertEquals(0, probes)
    }

    @Test fun `valid new link saves once and current link is ignored`() = runBlocking {
        var address = old
        var saves = 0
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, 0) }
        val importer = ClipboardBridgeImporter()
        val verify: suspend (String) -> BridgeAddressProbe = { BridgeAddressProbe.Reachable }
        assertEquals(ClipboardBridgeImporter.Result.Saved, importer.inspect(link, connection, verify, { address = it; saves++ }))
        assertEquals(link, address)
        assertEquals(ClipboardBridgeImporter.Result.Ignored, ClipboardBridgeImporter().inspect(link, connection, verify, { saves++ }))
        assertEquals(1, saves)
    }

    @Test fun `clipboard change or configuration change during verification supersedes old request`() = runBlocking {
        for (change in listOf("clipboard", "manual", "disable", "recovery", "invalidate")) {
            val importer = ClipboardBridgeImporter()
            var state = ClipboardBridgeImporter.Connection(true, false, old, 0)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var saves = 0
            val job = launch {
                assertEquals(ClipboardBridgeImporter.Result.Ignored, importer.inspect(link, { state }, {
                    entered.complete(Unit); release.await(); BridgeAddressProbe.Reachable
                }, { saves++ }))
            }
            entered.await()
            when (change) {
                "clipboard" -> importer.inspect("unrelated copied text", { state }, { error("must not probe") }, { saves++ })
                "manual" -> state = state.copy(address = "https://manual.test/#token=m", revision = 1)
                "disable" -> state = state.copy(enabled = false)
                "recovery" -> state = state.copy(connected = true)
                else -> importer.invalidate()
            }
            release.complete(Unit)
            job.join()
            assertEquals(0, saves, change)
        }
    }

    @Test fun `newer verified clipboard link wins over slower older link`() = runBlocking {
        val importer = ClipboardBridgeImporter()
        var address = old
        var revision = 0L
        val connection = { ClipboardBridgeImporter.Connection(true, false, address, revision) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val older = launch {
            assertEquals(ClipboardBridgeImporter.Result.Ignored, importer.inspect(link, connection, {
                entered.complete(Unit); release.await(); BridgeAddressProbe.Reachable
            }, { error("superseded clipboard must not save") }))
        }
        entered.await()
        val newest = "https://newest.test/#token=newest"
        assertEquals(ClipboardBridgeImporter.Result.Saved, importer.inspect(newest, connection,
            { BridgeAddressProbe.Reachable }, { address = it; revision++ }))
        release.complete(Unit)
        older.join()
        assertEquals(newest, address)
    }
}
