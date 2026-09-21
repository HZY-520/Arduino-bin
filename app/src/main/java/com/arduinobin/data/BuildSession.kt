package com.arduinobin.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 前台服务与 UI 之间共享的构建会话状态。构建发生在 [com.arduinobin.build.BuildService] 中，
 * UI 通过 [state] 实时观察进度与日志。
 */
object BuildSession {

    private val _state = MutableStateFlow(BuildUiState.fresh())
    val state = _state.asStateFlow()

    fun log(line: String) = _state.update { it.appendLog(line) }

    fun setStatus(status: BuildStatus, label: String = "") =
        _state.update { it.copy(status = status, progressLabel = label) }

    fun setProject(projectName: String, board: BoardPreset) =
        _state.update { it.copy(projectName = projectName, board = board) }

    fun setOutput(outputDir: String, artifacts: List<String>) =
        _state.update { it.copy(outputDir = outputDir, artifacts = artifacts) }

    fun reset() {
        _state.value = BuildUiState.fresh()
    }
}