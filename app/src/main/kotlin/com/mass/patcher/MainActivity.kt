package com.mass.patcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mass.patcher.service.ApkPatcherService
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var selectedFileTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var patchNameEditText: EditText
    private var selectedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        selectedFileTextView = findViewById(R.id.selectedFileTextView)
        progressBar = findViewById(R.id.progressBar)
        patchNameEditText = findViewById(R.id.patchNameEditText)

        val selectFileButton: Button = findViewById(R.id.selectFileButton)
        val patchButton: Button = findViewById(R.id.patchButton)
        val openPatchedButton: Button = findViewById(R.id.openPatchedButton)

        selectFileButton.setOnClickListener { selectApkFile() }
        patchButton.setOnClickListener { patchFile() }
        openPatchedButton.setOnClickListener { openPatchedFile() }

        updateStatus("Pronto para começar", false)
    }

    private fun selectApkFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive"))
        }
        startActivityForResult(intent, REQUEST_PICK_APK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_APK && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                selectedFile = copyUriToFile(uri)
                selectedFileTextView.text = "Arquivo: ${selectedFile?.name}"
                updateStatus("APK selecionado: ${selectedFile?.name}", false)
            }
        }
    }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val fileName = getFileNameFromUri(uri) ?: "selected.apk"
            val outputFile = File(getExternalFilesDir(null), fileName)
            inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outputFile
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao copiar arquivo: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return when {
            uri.scheme == "content" -> {
                val cursor = contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }
            uri.scheme == "file" -> File(uri.path!!).name
            else -> null
        }
    }

    private fun patchFile() {
        val file = selectedFile
        if (file == null) {
            Toast.makeText(this, "Selecione um APK primeiro", Toast.LENGTH_SHORT).show()
            return
        }

        val patchName = patchNameEditText.text.toString().trim()
        if (patchName.isEmpty()) {
            Toast.makeText(this, "Digite um nome para o patch", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatus("Iniciando patch...", true)
        lifecycleScope.launch {
            try {
                val patchedFile = ApkPatcherService.patchApk(file, patchName)
                updateStatus("Patch concluído com sucesso!", false)
                Toast.makeText(this@MainActivity, "APK patcheado: ${patchedFile.name}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                updateStatus("Erro: ${e.message}", false)
                Toast.makeText(this@MainActivity, "Erro ao patchar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPatchedFile() {
        val patchedDir = File(getExternalFilesDir(null), "patched")
        if (!patchedDir.exists() || patchedDir.listFiles().isNullOrEmpty()) {
            Toast.makeText(this, "Nenhum APK patcheado encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        val latestFile = patchedDir.listFiles()?.maxByOrNull { it.lastModified() }
        if (latestFile != null) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", latestFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao abrir arquivo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatus(message: String, isLoading: Boolean) {
        statusTextView.text = message
        progressBar.visibility = if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
    }

    companion object {
        private const val REQUEST_PICK_APK = 1001
    }
}
