package com.rearcue.poc.ui

import com.rearcue.poc.agent.CodexRemoteModel

internal data class CodexModelRow(
    val modelId: String?,
    val label: String,
    val note: String?,
    val checked: Boolean,
    val clearsManual: Boolean,
)

internal sealed interface CodexOutgoingModel {
    data class Send(val modelId: String?) : CodexOutgoingModel
    data object Unavailable : CodexOutgoingModel
}

/** 未手选时返回当前模型的显示名；两者都没有时返回 null，由界面显示「模型未知」。 */
internal fun codexModelLabel(
    manualId: String?,
    currentId: String?,
    currentName: String?,
    models: List<CodexRemoteModel>,
): String? {
    val shownId = manualId ?: currentId ?: return null
    models.firstOrNull { it.id == shownId }?.displayName?.let { return it }
    if (shownId == currentId && !currentName.isNullOrBlank()) return currentName
    return shownId
}

internal fun codexModelRows(
    manualId: String?,
    currentId: String?,
    currentName: String?,
    models: List<CodexRemoteModel>,
    unknownLabel: String,
    revertNote: String,
): List<CodexModelRow> {
    val shownId = manualId ?: currentId
    val rows = models.map { model ->
        CodexModelRow(
            modelId = model.id,
            label = model.displayName,
            note = null,
            checked = model.id == shownId,
            clearsManual = model.id == currentId,
        )
    }
    val currentListed = currentId != null && models.any { it.id == currentId }
    if (currentId != null && !currentListed) {
        return rows + CodexModelRow(
            modelId = null,
            label = currentName?.takeIf { it.isNotBlank() } ?: currentId,
            note = revertNote,
            checked = manualId == null,
            clearsManual = true,
        )
    }
    if (currentId == null) {
        return rows + CodexModelRow(
            modelId = null,
            label = unknownLabel,
            note = null,
            checked = manualId == null,
            clearsManual = true,
        )
    }
    return rows
}

/** 已知名单却没有手选模型时不发送；名单尚未读到时交给桥校验。 */
internal fun codexOutgoingModel(manualId: String?, knownModels: List<CodexRemoteModel>?): CodexOutgoingModel {
    if (manualId == null) return CodexOutgoingModel.Send(null)
    if (knownModels != null && knownModels.none { it.id == manualId }) return CodexOutgoingModel.Unavailable
    return CodexOutgoingModel.Send(manualId)
}
