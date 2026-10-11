package com.rearcue.poc.ui

import com.rearcue.poc.agent.CodexRemoteModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexModelSelectionTest {
    private val current = CodexRemoteModel("current", "当前模型", false)
    private val other = CodexRemoteModel("other", "另一个", true)

    @Test fun untouchedConversationShowsItsModelNotTheProviderDefault() {
        assertEquals("当前模型", codexModelLabel(null, "current", null, listOf(other, current)))
        assertEquals(null, codexModelLabel(null, null, null, listOf(other)))
    }

    @Test fun manualChoiceReplacesTheLabelUntilCancelled() {
        assertEquals("另一个", codexModelLabel("other", "current", "旧名字", listOf(current, other)))
        val rows = codexModelRows("other", "current", "旧名字", listOf(current, other), "模型未知", "只能改回，不能新选")
        assertEquals(listOf(false, true), rows.map { it.checked })
        assertTrue(rows.first { it.label == "当前模型" }.clearsManual)
        assertFalse(rows.any { it.label == "模型未知" })
    }

    @Test fun unknownAndUnavailableCurrentModelsRemainRevertRows() {
        val unknown = codexModelRows(null, null, null, listOf(other), "模型未知", "只能改回，不能新选")
        assertEquals("模型未知", unknown.last().label)
        assertTrue(unknown.last().checked && unknown.last().clearsManual)
        val missing = codexModelRows("other", "gone", "已经不能选", listOf(other), "模型未知", "只能改回，不能新选")
        assertEquals("已经不能选", missing.last().label)
        assertEquals("只能改回，不能新选", missing.last().note)
        assertFalse(missing.last().checked)
    }

    @Test fun unavailableManualModelIsNotSent() {
        assertEquals(CodexOutgoingModel.Send(null), codexOutgoingModel(null, listOf(other)))
        assertEquals(CodexOutgoingModel.Send("other"), codexOutgoingModel("other", listOf(other)))
        assertEquals(CodexOutgoingModel.Unavailable, codexOutgoingModel("gone", listOf(other)))
        assertEquals(CodexOutgoingModel.Send("gone"), codexOutgoingModel("gone", null))
    }
}
