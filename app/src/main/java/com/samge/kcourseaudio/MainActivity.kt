package com.samge.kcourseaudio

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.samge.kcourseaudio.ui.KAudioTheme
import com.samge.kcourseaudio.ui.MainViewModel
import com.samge.kcourseaudio.ui.PlayerApp
import com.samge.kcourseaudio.util.TreeUri

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /** 选 zip 文件（SAF）。 */
    private val pickZip =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { vm.importLocalZip(it) }
        }

    /** 选音频目录（SAF 目录树）。 */
    private val pickDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val path = TreeUri.toFilePath(uri)
            if (path == null) {
                android.widget.Toast.makeText(
                    this, "无法识别所选目录", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }
            // 公共目录需要"所有文件访问"授权
            if (Build.VERSION.SDK_INT >= 30 && !vm.settings.hasStoragePerm(java.io.File(path))) {
                try {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + packageName)
                    ))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                android.widget.Toast.makeText(
                    this,
                    "请授权后重试（所选目录: " + path + "）",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            vm.changeDir(path, autoMigrate = true) { _ -> }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KAudioTheme {
                PlayerApp(
                    vm,
                    onPickZip = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    onPickDir = { pickDir.launch(null) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.bindController()
        vm.refresh()
    }

    override fun onPause() {
        vm.saveNow()
        super.onPause()
    }
}
