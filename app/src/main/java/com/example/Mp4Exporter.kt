package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object Mp4Exporter {

  private data class WavHeader(
    val sampleRate: Int,
    val channels: Int,
    val dataOffset: Int,
    val dataSize: Int,
  ) {
    val totalSamplesPerChannel: Long
      get() = dataSize.toLong() / (channels * 2L)

    val durationUs: Long
      get() = (totalSamplesPerChannel * 1_000_000L) / sampleRate
  }

  fun exportToMp4(
    context: Context,
    wavFile: File,
    outputMp4: File,
    sourceVideoUri: Uri?,
    titleText: String,
    aspectMode: String,
    waveformPeaks: FloatArray,
    gainCurve: FloatArray,
    onProgress: (Int, String) -> Unit,
  ) {
    val wavHeader = parseWavHeader(wavFile)
    if (sourceVideoUri != null) {
      val remuxed =
        try {
          exportWithSourceVideo(
            context = context,
            wavFile = wavFile,
            wavHeader = wavHeader,
            outputMp4 = outputMp4,
            sourceVideoUri = sourceVideoUri,
            onProgress = onProgress,
          )
          true
        } catch (_: Throwable) {
          false
        }
      if (remuxed) return
    }

    exportWithGeneratedStudioVideo(
      wavFile = wavFile,
      wavHeader = wavHeader,
      outputMp4 = outputMp4,
      titleText = titleText,
      aspectMode = aspectMode,
      waveformPeaks = waveformPeaks,
      gainCurve = gainCurve,
      onProgress = onProgress,
    )
  }

  private fun parseWavHeader(wavFile: File): WavHeader {
    RandomAccessFile(wavFile, "r").use { raf ->
      val header = ByteArray(44)
      raf.readFully(header)
      val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
      val channels = buf.getShort(22).toInt().coerceAtLeast(1)
      val sampleRate = buf.getInt(24).coerceAtLeast(8000)

      // Scan chunks starting at byte 12 to find "data" reliably
      var pos = 12L
      val fileLen = raf.length()
      while (pos + 8 <= fileLen) {
        raf.seek(pos)
        val chunkHeader = ByteArray(8)
        raf.readFully(chunkHeader)
        val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
        val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (id == "data") {
          val safeSize = min(size.toLong(), fileLen - (pos + 8)).toInt().coerceAtLeast(0)
          return WavHeader(sampleRate, channels, (pos + 8).toInt(), safeSize)
        }
        pos += 8L + max(0, size)
      }

      return WavHeader(sampleRate, channels, 44, (fileLen - 44L).toInt().coerceAtLeast(0))
    }
  }

  private fun exportWithSourceVideo(
    context: Context,
    wavFile: File,
    wavHeader: WavHeader,
    outputMp4: File,
    sourceVideoUri: Uri,
    onProgress: (Int, String) -> Unit,
  ) {
    onProgress(15, "Reading source MP4 video track...")
    val extractor = MediaExtractor()
    extractor.setDataSource(context, sourceVideoUri, null)

    var videoTrackIdx = -1
    var videoFormat: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
      val fmt = extractor.getTrackFormat(i)
      val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
      if (mime.startsWith("video/")) {
        videoTrackIdx = i
        videoFormat = fmt
        break
      }
    }

    if (videoTrackIdx < 0 || videoFormat == null) {
      extractor.release()
      throw IllegalStateException("No video track found in source file")
    }

    extractor.selectTrack(videoTrackIdx)

    val audioFormat =
      MediaFormat.createAudioFormat(
          MediaFormat.MIMETYPE_AUDIO_AAC,
          wavHeader.sampleRate,
          wavHeader.channels,
        )
        .apply {
          setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
          setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
          setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }

    val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    audioEncoder.start()

    var muxer: MediaMuxer? = null
    var muxerStarted = false
    var muxVideoTrack = -1
    var muxAudioTrack = -1

    try {
      val audioRaf = RandomAccessFile(wavFile, "r")
      audioRaf.seek(wavHeader.dataOffset.toLong())

      val pcmChunk = ByteArray(4096)
      var bytesRemaining = wavHeader.dataSize.toLong()
      var samplesFed = 0L
      var audioInputDone = false
      var audioOutputDone = false
      val audioInfo = MediaCodec.BufferInfo()
      val bytesPerFrame = wavHeader.channels * 2

      val videoMaxBuf =
        if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
          videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
        } else {
          1024 * 1024
        }
      val videoBuf = ByteBuffer.allocate(videoMaxBuf)
      val videoInfo = MediaCodec.BufferInfo()
      var videoCopied = false
      val totalDurationUs = wavHeader.durationUs

      while (!audioOutputDone) {
        if (!audioInputDone) {
          val inIdx = audioEncoder.dequeueInputBuffer(5_000)
          if (inIdx >= 0) {
            val inBuf = audioEncoder.getInputBuffer(inIdx)!!
            inBuf.clear()
            if (bytesRemaining <= 0) {
              audioEncoder.queueInputBuffer(
                inIdx,
                0,
                0,
                (samplesFed * 1_000_000L) / wavHeader.sampleRate,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
              )
              audioInputDone = true
            } else {
              val toRead = min(min(pcmChunk.size.toLong(), inBuf.capacity().toLong()), bytesRemaining).toInt()
              val read = audioRaf.read(pcmChunk, 0, toRead)
              if (read > 0) {
                inBuf.put(pcmChunk, 0, read)
                val ptsUs = (samplesFed * 1_000_000L) / wavHeader.sampleRate
                samplesFed += (read / bytesPerFrame).toLong()
                bytesRemaining -= read
                audioEncoder.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
              } else {
                audioEncoder.queueInputBuffer(
                  inIdx,
                  0,
                  0,
                  (samplesFed * 1_000_000L) / wavHeader.sampleRate,
                  MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                audioInputDone = true
              }
            }
          }
        }

        val outIdx = audioEncoder.dequeueOutputBuffer(audioInfo, 5_000)
        when {
          outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
              muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            }
            muxVideoTrack = muxer.addTrack(videoFormat)
            muxAudioTrack = muxer.addTrack(audioEncoder.outputFormat)
            muxer.start()
            muxerStarted = true

            // Copy original video track up to audio duration
            onProgress(40, "Muxing original video track...")
            while (!videoCopied) {
              videoBuf.clear()
              val sampleSize = extractor.readSampleData(videoBuf, 0)
              if (sampleSize < 0) {
                videoCopied = true
                break
              }
              val sampleTime = extractor.sampleTime
              if (sampleTime > totalDurationUs) {
                videoCopied = true
                break
              }
              videoInfo.offset = 0
              videoInfo.size = sampleSize
              videoInfo.presentationTimeUs = sampleTime
              videoInfo.flags =
                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                  MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                  0
                }
              muxer.writeSampleData(muxVideoTrack, videoBuf, videoInfo)
              extractor.advance()
            }
            onProgress(65, "Encoding ducked AAC audio into MP4...")
          }
          outIdx >= 0 -> {
            val outBuf = audioEncoder.getOutputBuffer(outIdx)!!
            if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
              audioInfo.size > 0 &&
              muxerStarted
            ) {
              outBuf.position(audioInfo.offset)
              outBuf.limit(audioInfo.offset + audioInfo.size)
              muxer?.writeSampleData(muxAudioTrack, outBuf, audioInfo)
              val pct = 65 + ((audioInfo.presentationTimeUs * 30) / max(1L, totalDurationUs)).toInt()
              onProgress(pct.coerceIn(65, 95), "Finalizing MP4 audio...")
            }
            audioEncoder.releaseOutputBuffer(outIdx, false)
            if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              audioOutputDone = true
            }
          }
        }
      }
      audioRaf.close()
    } finally {
      runCatching { extractor.release() }
      runCatching {
        audioEncoder.stop()
        audioEncoder.release()
      }
      if (muxerStarted) {
        runCatching { muxer?.stop() }
      }
      runCatching { muxer?.release() }
    }
    onProgress(100, "MP4 export ready!")
  }

  private fun exportWithGeneratedStudioVideo(
    wavFile: File,
    wavHeader: WavHeader,
    outputMp4: File,
    titleText: String,
    aspectMode: String,
    waveformPeaks: FloatArray,
    gainCurve: FloatArray,
    onProgress: (Int, String) -> Unit,
  ) {
    val (width, height) =
      when (aspectMode) {
        "9:16" -> 544 to 960
        "1:1" -> 640 to 640
        else -> 960 to 544
      }
    val fps = 15
    val totalDurationUs = max(500_000L, wavHeader.durationUs)
    val totalVideoFrames = ((totalDurationUs * fps) / 1_000_000L).toInt().coerceAtLeast(1)

    onProgress(12, "Preparing Studio MP4 encoder (${width}x${height})...")

    // Pre-render static background + waveform onto baseBitmap once for high speed
    val baseBitmap = renderStaticBackdrop(width, height, titleText, waveformPeaks, gainCurve)
    val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val frameCanvas = Canvas(frameBitmap)
    val argbPixels = IntArray(width * height)

    val playheadPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
      }
    val playedFillPaint =
      Paint().apply {
        color = Color.argb(26, 34, 211, 238)
      }
    val timePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = (min(width, height) * 0.036f).coerceIn(18f, 28f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
      }

    val videoFormat =
      MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(
          MediaFormat.KEY_COLOR_FORMAT,
          MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        setInteger(MediaFormat.KEY_BIT_RATE, 1_800_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
      }

    val audioFormat =
      MediaFormat.createAudioFormat(
          MediaFormat.MIMETYPE_AUDIO_AAC,
          wavHeader.sampleRate,
          wavHeader.channels,
        )
        .apply {
          setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
          setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
          setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }

    val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    videoEncoder.start()
    audioEncoder.start()

    var muxer: MediaMuxer? = null
    var muxerStarted = false
    var outVideoFormat: MediaFormat? = null
    var outAudioFormat: MediaFormat? = null
    var muxVideoTrack = -1
    var muxAudioTrack = -1

    val pendingVideoPackets = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
    val pendingAudioPackets = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()

    val audioRaf = RandomAccessFile(wavFile, "r")
    audioRaf.seek(wavHeader.dataOffset.toLong())

    try {
      val pcmChunk = ByteArray(4096)
      var bytesRemaining = wavHeader.dataSize.toLong()
      var samplesFed = 0L
      val bytesPerFrame = wavHeader.channels * 2

      var videoFrameIdx = 0
      var videoInputDone = false
      var videoOutputDone = false
      var audioInputDone = false
      var audioOutputDone = false

      val videoInfo = MediaCodec.BufferInfo()
      val audioInfo = MediaCodec.BufferInfo()
      val totalSecStr = formatSeconds(totalDurationUs / 1_000_000.0)

      val stageMarginX = width * 0.08f
      val stageWidth = width - stageMarginX * 2f
      val waveTop = height * 0.34f
      val waveBottom = height * 0.72f

      while (!videoOutputDone || !audioOutputDone) {
        // 1. Feed Video
        if (!videoInputDone) {
          val vInIdx = videoEncoder.dequeueInputBuffer(2_000)
          if (vInIdx >= 0) {
            if (videoFrameIdx >= totalVideoFrames) {
              val ptsUs = (videoFrameIdx.toLong() * 1_000_000L) / fps
              videoEncoder.queueInputBuffer(vInIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              videoInputDone = true
            } else {
              val progress = videoFrameIdx.toFloat() / max(1, totalVideoFrames).toFloat()
              val ptsUs = (videoFrameIdx.toLong() * 1_000_000L) / fps

              frameCanvas.drawBitmap(baseBitmap, 0f, 0f, null)
              val playX = stageMarginX + stageWidth * progress
              frameCanvas.drawRect(stageMarginX, waveTop, playX, waveBottom, playedFillPaint)
              frameCanvas.drawLine(playX, waveTop - 10f, playX, waveBottom + 10f, playheadPaint)
              val curSecStr = formatSeconds(ptsUs / 1_000_000.0)
              frameCanvas.drawText("$curSecStr / $totalSecStr", width - stageMarginX, height * 0.84f, timePaint)

              frameBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
              val image = videoEncoder.getInputImage(vInIdx)
              val inBuf = videoEncoder.getInputBuffer(vInIdx)!!
              if (image != null) {
                writeArgbToYuv420Image(argbPixels, width, height, image)
                image.close()
              }
              videoEncoder.queueInputBuffer(vInIdx, 0, inBuf.capacity(), ptsUs, 0)
              videoFrameIdx++

              if (videoFrameIdx % 10 == 0 || videoFrameIdx == totalVideoFrames) {
                val pct = 15 + ((videoFrameIdx * 80) / max(1, totalVideoFrames))
                onProgress(pct.coerceIn(15, 95), "Rendering Studio MP4 (${videoFrameIdx}/$totalVideoFrames frames)...")
              }
            }
          }
        }

        // 2. Feed Audio
        if (!audioInputDone) {
          val aInIdx = audioEncoder.dequeueInputBuffer(2_000)
          if (aInIdx >= 0) {
            val inBuf = audioEncoder.getInputBuffer(aInIdx)!!
            inBuf.clear()
            if (bytesRemaining <= 0) {
              audioEncoder.queueInputBuffer(
                aInIdx,
                0,
                0,
                (samplesFed * 1_000_000L) / wavHeader.sampleRate,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
              )
              audioInputDone = true
            } else {
              val toRead = min(min(pcmChunk.size.toLong(), inBuf.capacity().toLong()), bytesRemaining).toInt()
              val read = audioRaf.read(pcmChunk, 0, toRead)
              if (read > 0) {
                inBuf.put(pcmChunk, 0, read)
                val ptsUs = (samplesFed * 1_000_000L) / wavHeader.sampleRate
                samplesFed += (read / bytesPerFrame).toLong()
                bytesRemaining -= read
                audioEncoder.queueInputBuffer(aInIdx, 0, read, ptsUs, 0)
              } else {
                audioEncoder.queueInputBuffer(
                  aInIdx,
                  0,
                  0,
                  (samplesFed * 1_000_000L) / wavHeader.sampleRate,
                  MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                audioInputDone = true
              }
            }
          }
        }

        // 3. Drain Video
        if (!videoOutputDone) {
          val vOutIdx = videoEncoder.dequeueOutputBuffer(videoInfo, 2_000)
          when {
            vOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              outVideoFormat = videoEncoder.outputFormat
            }
            vOutIdx >= 0 -> {
              val outBuf = videoEncoder.getOutputBuffer(vOutIdx)!!
              if ((videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && videoInfo.size > 0) {
                outBuf.position(videoInfo.offset)
                outBuf.limit(videoInfo.offset + videoInfo.size)
                if (muxerStarted) {
                  muxer?.writeSampleData(muxVideoTrack, outBuf, videoInfo)
                } else {
                  val copy = ByteArray(videoInfo.size)
                  outBuf.get(copy)
                  val infoCopy = MediaCodec.BufferInfo().apply {
                    set(0, copy.size, videoInfo.presentationTimeUs, videoInfo.flags)
                  }
                  pendingVideoPackets.add(copy to infoCopy)
                }
              }
              videoEncoder.releaseOutputBuffer(vOutIdx, false)
              if ((videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                videoOutputDone = true
              }
            }
          }
        }

        // 4. Drain Audio
        if (!audioOutputDone) {
          val aOutIdx = audioEncoder.dequeueOutputBuffer(audioInfo, 2_000)
          when {
            aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              outAudioFormat = audioEncoder.outputFormat
            }
            aOutIdx >= 0 -> {
              val outBuf = audioEncoder.getOutputBuffer(aOutIdx)!!
              if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && audioInfo.size > 0) {
                outBuf.position(audioInfo.offset)
                outBuf.limit(audioInfo.offset + audioInfo.size)
                if (muxerStarted) {
                  muxer?.writeSampleData(muxAudioTrack, outBuf, audioInfo)
                } else {
                  val copy = ByteArray(audioInfo.size)
                  outBuf.get(copy)
                  val infoCopy = MediaCodec.BufferInfo().apply {
                    set(0, copy.size, audioInfo.presentationTimeUs, audioInfo.flags)
                  }
                  pendingAudioPackets.add(copy to infoCopy)
                }
              }
              audioEncoder.releaseOutputBuffer(aOutIdx, false)
              if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                audioOutputDone = true
              }
            }
          }
        }

        // 5. Start Muxer once both output formats are ready
        if (!muxerStarted && outVideoFormat != null && outAudioFormat != null) {
          muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
          muxVideoTrack = muxer.addTrack(outVideoFormat)
          muxAudioTrack = muxer.addTrack(outAudioFormat)
          muxer.start()
          muxerStarted = true

          for ((bytes, info) in pendingVideoPackets) {
            muxer.writeSampleData(muxVideoTrack, ByteBuffer.wrap(bytes), info)
          }
          pendingVideoPackets.clear()
          for ((bytes, info) in pendingAudioPackets) {
            muxer.writeSampleData(muxAudioTrack, ByteBuffer.wrap(bytes), info)
          }
          pendingAudioPackets.clear()
        }
      }
    } finally {
      runCatching { audioRaf.close() }
      runCatching {
        videoEncoder.stop()
        videoEncoder.release()
      }
      runCatching {
        audioEncoder.stop()
        audioEncoder.release()
      }
      if (muxerStarted) {
        runCatching { muxer?.stop() }
      }
      runCatching { muxer?.release() }
      baseBitmap.recycle()
      frameBitmap.recycle()
    }
    onProgress(100, "MP4 export ready!")
  }

  private fun renderStaticBackdrop(
    width: Int,
    height: Int,
    titleText: String,
    peaks: FloatArray,
    gainCurve: FloatArray,
  ): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)

    val bgPaint =
      Paint().apply {
        shader =
          LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Color.parseColor("#0B0F14"),
            Color.parseColor("#111926"),
            Shader.TileMode.CLAMP,
          )
      }
    c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    val marginX = width * 0.08f
    val stageW = width - marginX * 2f

    // Title & badge
    val badgePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A222E")
      }
    val badgeRect = RectF(marginX, height * 0.10f, marginX + 210f, height * 0.10f + 36f)
    c.drawRoundRect(badgeRect, 18f, 18f, badgePaint)

    val badgeTextPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
    c.drawText("AUTODUCK STUDIO", marginX + 18f, height * 0.10f + 24f, badgeTextPaint)

    val titlePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F5F9")
        textSize = (min(width, height) * 0.052f).coerceIn(24f, 40f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
    val safeTitle = if (titleText.length > 36) titleText.take(33) + "..." else titleText
    c.drawText(safeTitle, marginX, height * 0.25f, titlePaint)

    // Waveform Card
    val waveTop = height * 0.34f
    val waveBottom = height * 0.72f
    val waveHeight = waveBottom - waveTop
    val cardPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#080C11")
      }
    val cardRect = RectF(marginX, waveTop, marginX + stageW, waveBottom)
    c.drawRoundRect(cardRect, 18f, 18f, cardPaint)

    // Draw voiceover peaks
    val barCount = 96
    val barStep = stageW / barCount
    val centerY = waveTop + waveHeight * 0.5f
    val barPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 34, 211, 238)
      }
    for (i in 0 until barCount) {
      val idx = if (peaks.isNotEmpty()) ((i.toFloat() / barCount) * peaks.size).toInt().coerceIn(0, peaks.size - 1) else 0
      val amp = if (peaks.isNotEmpty()) peaks[idx].coerceIn(0.05f, 1f) else 0.25f
      val bh = max(6f, amp * waveHeight * 0.82f)
      val x = marginX + i * barStep
      c.drawRoundRect(x + 1f, centerY - bh / 2f, x + max(3f, barStep - 2f), centerY + bh / 2f, 2f, 2f, barPaint)
    }

    // Draw ducking gain curve
    if (gainCurve.size >= 2) {
      val path = Path()
      for (i in gainCurve.indices) {
        val x = marginX + (i.toFloat() / (gainCurve.size - 1)) * stageW
        val y = waveTop + waveHeight * (1f - gainCurve[i].coerceIn(0f, 1f))
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
      }
      val curvePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#F59E0B")
          style = Paint.Style.STROKE
          strokeWidth = 3.5f
        }
      c.drawPath(path, curvePaint)
    }

    val legendPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = (min(width, height) * 0.032f).coerceIn(16f, 22f)
      }
    c.drawText("Cyan: Voiceover   •   Amber: Auto-Ducked Music", marginX, height * 0.84f, legendPaint)

    return bmp
  }

  private fun writeArgbToYuv420Image(
    argb: IntArray,
    width: Int,
    height: Int,
    image: Image,
  ) {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer

    val yRowStride = yPlane.rowStride
    val yPixStride = yPlane.pixelStride
    val uRowStride = uPlane.rowStride
    val uPixStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixStride = vPlane.pixelStride

    for (j in 0 until height) {
      val rowOffset = j * width
      val yLineOffset = j * yRowStride
      val uvRow = j shr 1
      val uLineOffset = uvRow * uRowStride
      val vLineOffset = uvRow * vRowStride

      for (i in 0 until width) {
        val px = argb[rowOffset + i]
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF

        val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        yBuf.put(yLineOffset + i * yPixStride, yVal.coerceIn(0, 255).toByte())

        if ((j and 1) == 0 && (i and 1) == 0) {
          val uvCol = i shr 1
          val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
          val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
          uBuf.put(uLineOffset + uvCol * uPixStride, uVal.coerceIn(0, 255).toByte())
          vBuf.put(vLineOffset + uvCol * vPixStride, vVal.coerceIn(0, 255).toByte())
        }
      }
    }
  }

  private fun formatSeconds(sec: Double): String {
    val total = max(0, sec.toInt())
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
  }
}
