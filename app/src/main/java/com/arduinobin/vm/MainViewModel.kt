package com.arduinobin.vm

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import com.arduinobin.build.BuildService
import com.arduinobin.data.BuildSession
import com.arduinobin.data.BuildUiState
import com.arduinobin.termux.TermuxEnv
import com.arduinobin.util.LibraryInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel : ViewModel() {

    val buildState: StateFlow<BuildUiState> = BuildSession.state

    fun startBuild(
        context: Context,
        zipUri: Uri,
        boardId: String,
        projectName: String,
        libraries: List<String>,
    ) {
        BuildService.start(context, zipUri, boardId, projectName, libraries)
    }

    /** 把一个库 zip 解压安装到 arduino-cli 用户库目录。 */
    suspend fun installLibraryFromZip(context: Context, uri: Uri): LibraryInstaller.Result =
        withContext(Dispatchers.IO) {
            val librariesDir = File(TermuxEnv.workspaceDir, "libraries")
            LibraryInstaller.install(context, uri, librariesDir)
        }

    /** 列出所有已安装的库。 */
    suspend fun listInstalledLibraries(): List<LibraryInstaller.LibraryInfo> =
        withContext(Dispatchers.IO) {
            LibraryInstaller.listInstalled(File(TermuxEnv.workspaceDir, "libraries"))
        }

    /** 删除一个已安装的库，返回是否成功。 */
    suspend fun deleteLibrary(dirName: String): Boolean =
        withContext(Dispatchers.IO) {
            LibraryInstaller.delete(dirName, File(TermuxEnv.workspaceDir, "libraries"))
        }

    /** 把构建产物复制到用户通过 SAF 选择的目录树。 */
    fun exportArtifacts(context: Context, treeUri: Uri, artifacts: List<String>): Int {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
        var copied = 0
        artifacts.forEach { path ->
            val src = File(path)
            if (!src.exists()) return@forEach
            val dst = tree.createFile("application/octet-stream", src.name)
                ?: tree.findFile(src.name)
            dst?.let {
                context.contentResolver.openOutputStream(it.uri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                }
                copied++
            }
        }
        return copied
    }
}