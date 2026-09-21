package com.arduinobin.data

/** 一个构建任务的状态快照。 */
enum class BuildStatus { IDLE, PREPARING, DOWNLOADING, COMPILING, SUCCESS, FAILED, CANCELLED }

data class BuildUiState(
    val status: BuildStatus = BuildStatus.IDLE,
    val progressLabel: String = "",
    val log: List<String> = listOf("就绪。请选择一个 Arduino 项目 zip 开始。"),
    val projectName: String? = null,
    val board: BoardPreset? = null,
    val outputDir: String? = null,
    val artifacts: List<String> = emptyList(),
) {
    fun appendLog(line: String): BuildUiState = copy(log = log + line)

    companion object {
        fun fresh(): BuildUiState = BuildUiState()
    }
}