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
import org.json.JSONObject

class MainActivity : ComponentActivity() {

  private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
  private var pendingPickerRole: String = "voice"
  private var sourceVideoUri: Uri? = null

  private var pendingLegacyExportFile: File? = null
  private var pendingLegacyMimeType: String = "audio/wav"
  private var onLegacySavedCallback: ((String, Uri, String) -> Unit)? = null

  private val openMediaLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      val callback = pendingFileCallback
      pendingFileCallback = null
      if (callback != null) {
        if (uri != null) {
          if (pendingPickerRole == "voice") {
            val mime = contentResolver.getType(uri) ?: ""
            sourceVideoUri = if (mime.startsWith("video/")) uri else null
          }
          callback.onReceiveValue(arrayOf(uri))
        } else {
          callback.onReceiveValue(null)
        }
      }
    }

  private val createWavLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri: Uri? ->
      completeLegacyCreateDocument(uri)
    }

  private val createMp4Launcher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri: Uri? ->
      completeLegacyCreateDocument(uri)
    }

  private fun completeLegacyCreateDocument(uri: Uri?) {
    val sourceFile = pendingLegacyExportFile
    val mimeType = pendingLegacyMimeType
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
            onLegacySavedCallback?.invoke(fileName, uri, mimeType)
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
            onSetPickerRole = { role -> pendingPickerRole = role },
            onClearSourceVideo = { sourceVideoUri = null },
            onShowFileChooser = { callback ->
              pendingFileCallback?.onReceiveValue(null)
              pendingFileCallback = callback
              if (pendingPickerRole == "voice") {
                openMediaLauncher.launch(arrayOf("audio/*", "video/mp4", "video/*", "application/ogg"))
              } else {
                openMediaLauncher.launch(arrayOf("audio/*", "application/ogg"))
              }
            },
            onExportRequest = { request, webView ->
              handleMediaExport(
                request = request,
                webView = webView,
                onSaved = { savedName, shareUri, mimeType ->
                  scope.launch {
                    val msgRes =
                      if (mimeType == "video/mp4") R.string.export_mp4_saved_message
                      else R.string.export_saved_message
                    val result =
                      snackbarHostState.showSnackbar(
                        message = getString(msgRes, savedName),
                        actionLabel = getString(R.string.share_action),
                        duration = SnackbarDuration.Long,
                      )
                    if (result == SnackbarResult.ActionPerformed) {
                      shareMediaUri(shareUri, mimeType)
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

  private fun handleMediaExport(
    request: ExportRequest,
    webView: WebView,
    onSaved: (String, Uri, String) -> Unit,
  ) {
    fun notifyWebProgress(pct: Int, msg: String, done: Boolean = false, error: String? = null) {
      val safeMsg = JSONObject.quote(msg)
      val safeErr = if (error != null) JSONObject.quote(error) else "null"
      webView.post {
        webView.evaluateJavascript(
          "window.onAndroidExportProgress && window.onAndroidExportProgress($pct, $safeMsg, $done, $safeErr);",
          null,
        )
      }
    }

    if (request.format == "mp4") {
      CoroutineScope(Dispatchers.IO).launch {
        try {
          val exportDir = File(cacheDir, "exports").apply { mkdirs() }
          val mp4Name = request.fileName.replace(Regex("\\.wav$", RegexOption.IGNORE_CASE), "") + ".mp4"
          val outMp4 = File(exportDir, mp4Name)
          if (outMp4.exists()) outMp4.delete()

          Mp4Exporter.exportToMp4(
            context = this@MainActivity,
            wavFile = request.wavFile,
            outputMp4 = outMp4,
            sourceVideoUri = if (request.useSourceVideo) sourceVideoUri else null,
            titleText = request.titleText,
            aspectMode = request.aspectMode,
            waveformPeaks = request.peaks,
            gainCurve = request.gainCurve,
            onProgress = { pct, status -> notifyWebProgress(pct, status) },
          )

          saveFileToPublicStorage(
            file = outMp4,
            displayName = mp4Name,
            mimeType = "video/mp4",
            isVideo = true,
            onSaved = { savedName, shareUri ->
              notifyWebProgress(100, "MP4 Saved: $savedName", done = true)
              onSaved(savedName, shareUri, "video/mp4")
            },
          )
        } catch (t: Throwable) {
          notifyWebProgress(0, "MP4 Export Failed", done = true, error = t.message ?: "Encoding error")
        }
      }
    } else {
      saveFileToPublicStorage(
        file = request.wavFile,
        displayName = request.fileName,
        mimeType = "audio/wav",
        isVideo = false,
        onSaved = { savedName, shareUri ->
          notifyWebProgress(100, "WAV Saved: $savedName", done = true)
          onSaved(savedName, shareUri, "audio/wav")
        },
      )
    }
  }

  private fun saveFileToPublicStorage(
    file: File,
    displayName: String,
    mimeType: String,
    isVideo: Boolean,
    onSaved: (String, Uri) -> Unit,
  ) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      CoroutineScope(Dispatchers.IO).launch {
        val values =
          ContentValues().apply {
            if (isVideo) {
              put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
              put(MediaStore.Video.Media.MIME_TYPE, mimeType)
              put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + File.separator + "AutoDuck",
              )
              put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
              put(MediaStore.Downloads.DISPLAY_NAME, displayName)
              put(MediaStore.Downloads.MIME_TYPE, mimeType)
              put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + "AutoDuck",
              )
              put(MediaStore.Downloads.IS_PENDING, 1)
            }
          }

        val collectionUri =
          if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
          else MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val resolver = contentResolver
        val itemUri = resolver.insert(collectionUri, values)
        if (itemUri != null) {
          runCatching {
            resolver.openOutputStream(itemUri)?.use { out ->
              file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
          }
          val shareUri =
            FileProvider.getUriForFile(
              this@MainActivity,
              "${packageName}.fileprovider",
              file,
            )
          withContext(Dispatchers.Main) { onSaved(displayName, shareUri) }
        }
      }
    } else {
      CoroutineScope(Dispatchers.Main).launch {
        pendingLegacyExportFile = file
        pendingLegacyMimeType = mimeType
        onLegacySavedCallback = { name, uri, _ -> onSaved(name, uri) }
        if (isVideo) {
          createMp4Launcher.launch(displayName)
        } else {
          createWavLauncher.launch(displayName)
        }
      }
    }
  }

  private fun shareMediaUri(uri: Uri, mimeType: String) {
    val shareIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = mimeType
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

data class ExportRequest(
  val wavFile: File,
  val fileName: String,
  val format: String,
  val aspectMode: String,
  val titleText: String,
  val useSourceVideo: Boolean,
  val peaks: FloatArray,
  val gainCurve: FloatArray,
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AutoDuckWebViewScreen(
  modifier: Modifier = Modifier,
  onSetPickerRole: (String) -> Unit,
  onClearSourceVideo: () -> Unit,
  onShowFileChooser: (ValueCallback<Array<Uri>>) -> Unit,
  onExportRequest: (ExportRequest, WebView) -> Unit,
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

      addJavascriptInterface(
        AndroidAudioBridge(
          context = context,
          onSetPickerRole = onSetPickerRole,
          onClearSourceVideo = onClearSourceVideo,
          onExportReady = { req -> onExportRequest(req, this) },
        ),
        "AndroidBridge",
      )
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
  private val onSetPickerRole: (String) -> Unit,
  private val onClearSourceVideo: () -> Unit,
  private val onExportReady: (ExportRequest) -> Unit,
) {
  private var currentFile: File? = null
  private var currentFileName: String = "autoduck_mix.wav"
  private var currentFormat: String = "wav"
  private var currentAspect: String = "16:9"
  private var currentTitle: String = "AutoDuck Studio"
  private var currentUseSourceVideo: Boolean = false
  private var currentPeaks: FloatArray = FloatArray(0)
  private var currentCurve: FloatArray = FloatArray(0)
  private var currentOutput: FileOutputStream? = null

  @JavascriptInterface
  fun setPendingPickerRole(role: String) {
    onSetPickerRole(role)
  }

  @JavascriptInterface
  fun clearSourceVideo() {
    onClearSourceVideo()
  }

  @JavascriptInterface
  fun beginExport(
    fileName: String,
    format: String,
    aspectMode: String,
    titleText: String,
    useSourceVideo: Boolean,
    peaksCsv: String,
    curveCsv: String,
  ) {
    closeStreamQuietly()
    val safeName = sanitizeWavName(fileName)
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(exportDir, safeName)
    currentFile = outFile
    currentFileName = safeName
    currentFormat = if (format.equals("mp4", ignoreCase = true)) "mp4" else "wav"
    currentAspect = aspectMode
    currentTitle = titleText.ifBlank { "AutoDuck Studio" }
    currentUseSourceVideo = useSourceVideo
    currentPeaks = parseFloatCsv(peaksCsv)
    currentCurve = parseFloatCsv(curveCsv)
    currentOutput = FileOutputStream(outFile, false)
  }

  @JavascriptInterface
  fun beginWavSave(fileName: String) {
    beginExport(fileName, "wav", "16:9", "AutoDuck Studio", false, "", "")
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
      onExportReady(
        ExportRequest(
          wavFile = file,
          fileName = name,
          format = currentFormat,
          aspectMode = currentAspect,
          titleText = currentTitle,
          useSourceVideo = currentUseSourceVideo,
          peaks = currentPeaks,
          gainCurve = currentCurve,
        ),
      )
    }
  }

  @JavascriptInterface
  fun saveWavBase64(fileName: String, base64Data: String) {
    beginWavSave(fileName)
    appendWavChunk(base64Data)
    finishWavSave()
  }

  private fun parseFloatCsv(csv: String): FloatArray {
    if (csv.isBlank()) return FloatArray(0)
    val parts = csv.split(',')
    val arr = FloatArray(parts.size)
    for (i in parts.indices) {
      arr[i] = parts[i].toFloatOrNull() ?: 0f
    }
    return arr
  }

  private fun closeStreamQuietly() {
    runCatching {
      currentOutput?.flush()
      currentOutput?.close()
    }
    currentOutput = null
  }

  private fun sanitizeWavName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "autoduck_mix.wav" }
    return if (base.endsWith(".wav", ignoreCase = true)) base else "$base.wav"
  }
}
