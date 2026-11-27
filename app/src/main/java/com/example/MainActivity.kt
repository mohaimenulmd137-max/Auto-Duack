package com.example

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.ui.theme.AutoDuckStudioTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

  private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
  private var pendingLegacyExportFile: File? = null
  private var onLegacySavedCallback: ((String, Uri) -> Unit)? = null

  private val openAudioLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      val callback = pendingFileCallback
      pendingFileCallback = null
      if (callback != null) {
        if (uri != null) {
          callback.onReceiveValue(arrayOf(uri))
        } else {
          callback.onReceiveValue(null)
        }
      }
    }

  private val createWavLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri: Uri? ->
      val sourceFile = pendingLegacyExportFile
      pendingLegacyExportFile = null
      if (uri != null && sourceFile != null && sourceFile.exists()) {
        val fileName = sourceFile.name
        CoroutineScope(Dispatchers.IO).launch {
          runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
              sourceFile.inputStream().use { input -> input.copyTo(out) }
            }
          }.onSuccess {
            withContext(Dispatchers.Main) {
              onLegacySavedCallback?.invoke(fileName, uri)
            }
          }
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
    )

    setContent {
      AutoDuckStudioTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        Scaffold(
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
          containerColor = MaterialTheme.colorScheme.background,
          contentWindowInsets = WindowInsets.safeDrawing,
          snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { innerPadding ->
          AutoDuckWebViewScreen(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            onShowFileChooser = { callback ->
              pendingFileCallback?.onReceiveValue(null)
              pendingFileCallback = callback
              openAudioLauncher.launch(arrayOf("audio/*", "application/ogg"))
            },
            onExportReady = { fileName, tempFile ->
              handleWavExport(
                fileName = fileName,
                tempFile = tempFile,
                onSaved = { savedName, shareUri ->
                  scope.launch {
                    val result =
                      snackbarHostState.showSnackbar(
                        message = getString(R.string.export_saved_message, savedName),
                        actionLabel = getString(R.string.share_action),
                        duration = SnackbarDuration.Long,
                      )
                    if (result == SnackbarResult.ActionPerformed) {
                      shareAudioUri(shareUri)
                    }
                  }
                },
              )
            },
          )
        }
      }
    }
  }

  private fun handleWavExport(
    fileName: String,
    tempFile: File,
    onSaved: (String, Uri) -> Unit,
  ) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      CoroutineScope(Dispatchers.IO).launch {
        val values =
          ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
            put(
              MediaStore.Downloads.RELATIVE_PATH,
              Environment.DIRECTORY_DOWNLOADS + File.separator + "AutoDuck",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
          }

        val resolver = contentResolver
        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (itemUri != null) {
          runCatching {
            resolver.openOutputStream(itemUri)?.use { out ->
              tempFile.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
          }
          val shareUri =
            FileProvider.getUriForFile(
              this@MainActivity,
              "${packageName}.fileprovider",
              tempFile,
            )
          withContext(Dispatchers.Main) { onSaved(fileName, shareUri) }
        }
      }
    } else {
      pendingLegacyExportFile = tempFile
      onLegacySavedCallback = onSaved
      createWavLauncher.launch(fileName)
    }
  }

  private fun shareAudioUri(uri: Uri) {
    val shareIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    startActivity(Intent.createChooser(shareIntent, getString(R.string.export_share_chooser)))
  }

  override fun onDestroy() {
    pendingFileCallback?.onReceiveValue(null)
    pendingFileCallback = null
    super.onDestroy()
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AutoDuckWebViewScreen(
  modifier: Modifier = Modifier,
  onShowFileChooser: (ValueCallback<Array<Uri>>) -> Unit,
  onExportReady: (String, File) -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val webView = remember {
    WebView(context).apply {
      setBackgroundColor(AndroidColor.parseColor("#0B0F14"))
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      settings.allowFileAccess = true
      settings.allowContentAccess = true
      settings.cacheMode = WebSettings.LOAD_DEFAULT

      webViewClient =
        object : WebViewClient() {
          override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
          ): Boolean {
            return false
          }
        }

      webChromeClient =
        object : WebChromeClient() {
          override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
          ): Boolean {
            if (filePathCallback == null) return false
            onShowFileChooser(filePathCallback)
            return true
          }
        }

      addJavascriptInterface(AndroidAudioBridge(context, onExportReady), "AndroidBridge")
      loadUrl("file:///android_asset/index.html")
    }
  }

  DisposableEffect(webView) {
    onDispose {
      webView.stopLoading()
      webView.destroy()
    }
  }

  Box(modifier = modifier.testTag("autoduck_webview")) {
    AndroidView(
      factory = { webView },
      modifier = Modifier.fillMaxSize(),
    )
  }
}

private class AndroidAudioBridge(
  private val context: Context,
  private val onExportReady: (String, File) -> Unit,
) {
  private var currentFile: File? = null
  private var currentFileName: String = "autoduck_mix.wav"
  private var currentOutput: FileOutputStream? = null

  @JavascriptInterface
  fun beginWavSave(fileName: String) {
    closeStreamQuietly()
    val safeName = sanitizeFileName(fileName)
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(exportDir, safeName)
    currentFile = outFile
    currentFileName = safeName
    currentOutput = FileOutputStream(outFile, false)
  }

  @JavascriptInterface
  fun appendWavChunk(base64Chunk: String) {
    val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
    currentOutput?.write(bytes)
  }

  @JavascriptInterface
  fun finishWavSave() {
    val file = currentFile
    val name = currentFileName
    closeStreamQuietly()
    if (file != null && file.exists()) {
      onExportReady(name, file)
    }
  }

  @JavascriptInterface
  fun saveWavBase64(fileName: String, base64Data: String) {
    beginWavSave(fileName)
    appendWavChunk(base64Data)
    finishWavSave()
  }

  private fun closeStreamQuietly() {
    runCatching {
      currentOutput?.flush()
      currentOutput?.close()
    }
    currentOutput = null
  }

  private fun sanitizeFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "autoduck_mix.wav" }
    return if (base.endsWith(".wav", ignoreCase = true)) base else "$base.wav"
  }
}
