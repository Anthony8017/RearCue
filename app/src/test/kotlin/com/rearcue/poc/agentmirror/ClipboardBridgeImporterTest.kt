package com.rearcue.poc.agentmirror

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardBridgeImporterTest {
    private val link = "https://new.test/#token=valid"
    private val old = "https://old.test/#token=old"

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
