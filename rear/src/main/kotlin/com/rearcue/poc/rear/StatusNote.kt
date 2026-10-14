package com.rearcue.poc.rear

/**
 * 状态提示的显隐步骤（GLOSSARY.md「Status Note」）。
 * 同一会话换字保持显示；换会话或整行消失才淡出，新会话再单独淡入。
 */
internal data class StatusNoteFrame(val sessionId: String?, val text: String)

internal sealed interface StatusNoteCommand {
    data object Hide : StatusNoteCommand
    data class Show(val frame: StatusNoteFrame) : StatusNoteCommand
    data class Replace(val frame: StatusNoteFrame) : StatusNoteCommand
    data class Swap(val frame: StatusNoteFrame) : StatusNoteCommand
}

internal fun statusNoteCommand(
    shown: StatusNoteFrame?,
    visible: Boolean,
    target: StatusNoteFrame?,
): StatusNoteCommand? = when {
    target == null -> if (visible) StatusNoteCommand.Hide else null
    shown == null || !visible -> StatusNoteCommand.Show(target)
    shown.sessionId == target.sessionId ->
        if (shown.text == target.text) null else StatusNoteCommand.Replace(target)
    else -> StatusNoteCommand.Swap(target)
}
