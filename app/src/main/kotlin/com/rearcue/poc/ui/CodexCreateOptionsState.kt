package com.rearcue.poc.ui

import com.rearcue.poc.agent.CodexRemoteDefaults
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemoteRequest

internal data class CodexOptionsRead(val generation: Long, val projectId: String?, val model: String?)

/** 单次新建窗口的选择状态；草稿持久化由界面处理，选项不写入手机偏好。 */
internal data class CodexCreateOptionsState(
    val options: CodexRemoteOptions,
    val projectId: String?,
    val prompt: String,
    val pending: CodexOptionsRead?,
    val manualModelId: String? = null,
    val effortId: String? = null,
    val permissionId: String? = null,
    val readFailed: Boolean = false,
    private val generation: Long = 1,
    private val effortWasChosen: Boolean = false,
    private val permissionWasChosen: Boolean = false,
) {
    val project get() = options.projectForId(projectId)
    val modelId get() = manualModelId ?: options.defaults.model
    val model get() = options.models.firstOrNull { it.id == modelId }
    val effort get() = options.efforts.firstOrNull { it.id == effortId }
    val permission get() = options.permissions.firstOrNull { it.id == permissionId }
    val effortNeedsReselection get() = effortId != null && effort == null
    val permissionNeedsReselection get() = permissionId != null && permission == null
    val canCreate get() = pending == null && !readFailed && project != null &&
        model != null && effort != null && permission != null && prompt.isNotBlank()

    fun selectProject(id: String): CodexCreateOptionsState {
        val selected = options.projectForId(id) ?: return this
        if (selected.id == projectId) return this
        return copy(
            projectId = selected.id, generation = generation + 1,
            pending = CodexOptionsRead(generation + 1, selected.id, manualModelId),
        )
    }

    /** 点默认模型会取消手选；显示默认模型本身不是手选。 */
    fun selectModel(id: String?): CodexCreateOptionsState {
        if (id != null && options.models.none { it.id == id }) return this
        val manual = id?.takeUnless { it == options.defaults.model }
        if (manual == manualModelId) return this
        return copy(
            manualModelId = manual, generation = generation + 1,
            pending = CodexOptionsRead(generation + 1, projectId, manual),
        )
    }

    fun selectEffort(id: String): CodexCreateOptionsState =
        if (pending != null || options.efforts.none { it.id == id }) this
        else copy(effortId = id, effortWasChosen = true)

    fun selectPermission(id: String): CodexCreateOptionsState =
        if (pending != null || options.permissions.none { it.id == id }) this
        else copy(permissionId = id, permissionWasChosen = true)

    fun retry(): CodexCreateOptionsState = copy(
        generation = generation + 1,
        pending = CodexOptionsRead(generation + 1, projectId, manualModelId),
        readFailed = false,
    )

    fun loaded(request: CodexOptionsRead, response: CodexRemoteOptions?): CodexCreateOptionsState {
        if (pending != request || projectId != request.projectId || manualModelId != request.model) return this
        if (response == null) return copy(pending = null, readFailed = true)
        val context = response.context
        if (context == null || context.projectId != request.projectId ||
            context.model != (request.model ?: response.defaults.model)
        ) return copy(pending = null, readFailed = true)
        return copy(
            options = response, pending = null, readFailed = false,
            effortId = if (effortWasChosen) effortId else response.defaults.effort,
            permissionId = if (permissionWasChosen) permissionId else response.defaults.permission,
        )
    }

    fun createRequest(requestId: String): CodexRemoteRequest? {
        if (!canCreate) return null
        return CodexRemoteRequest(
            requestId, prompt.trim(), projectId = project!!.id, workspace = project!!.workspace,
            model = modelId, effort = effortId, permission = permissionId,
        )
    }

    companion object {
        fun open(seed: CodexRemoteOptions?, rememberedProjectId: String?, prompt: String): CodexCreateOptionsState {
            val options = (seed ?: CodexRemoteOptions(emptyList(), emptyList())).copy(
                defaults = CodexRemoteDefaults(), efforts = emptyList(), permissions = emptyList(),
            )
            val project = options.projectForId(rememberedProjectId) ?: options.projects.firstOrNull()
            return CodexCreateOptionsState(options, project?.id, prompt, CodexOptionsRead(1, project?.id, null))
        }
    }
}
