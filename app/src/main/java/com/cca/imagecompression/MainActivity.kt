package com.cca.imagecompression

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var layEmptyState: LinearLayout
    private lateinit var rvImages: RecyclerView
    private lateinit var layBatchProgress: LinearLayout
    private lateinit var txtBatchProgressLabel: TextView
    private lateinit var txtBatchPercent: TextView
    private lateinit var pbBatchProgress: ProgressBar

    // Summary panel
    private lateinit var layStatsSummary: LinearLayout
    private lateinit var txtSummarySavings: TextView

    private lateinit var txtQualityVal: TextView
    private lateinit var txtScaleVal: TextView
    private lateinit var sbQuality: SeekBar
    private lateinit var sbScale: SeekBar
    
    // Preset Buttons
    private lateinit var btnPresetUltra: Button
    private lateinit var btnPresetHigh: Button
    private lateinit var btnPresetStandard: Button
    private lateinit var btnPresetMax: Button
    
    // EXIF Switch
    private lateinit var swKeepExif: SwitchCompat

    private lateinit var btnSelect: Button
    private lateinit var btnClear: Button
    private lateinit var btnCompress: Button
    private lateinit var btnSaveToGallery: Button

    private val imageList = ArrayList<CompressedImage>()
    private lateinit var adapter: ImageAdapter
    private var isProcessing = false
    private var isCompressed = false // 标记是否完成了上一轮压缩

    // 现代多选 PhotoPicker
    private val pickMultipleMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            addSelectedImages(uris)
        }
    }

    // 传统多选降级选择器
    private val pickMediaLegacy = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uris = ArrayList<Uri>()
            val clipData = result.data?.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else {
                result.data?.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) {
                addSelectedImages(uris)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 配置全局崩溃捕获，接管所有闪退
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra("error", throwable.stackTraceToString())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }

        setContentView(R.layout.activity_main)

        // 初始化主工作区
        layEmptyState = findViewById(R.id.layEmptyState)
        rvImages = findViewById(R.id.rvImages)
        layBatchProgress = findViewById(R.id.layBatchProgress)
        txtBatchProgressLabel = findViewById(R.id.txtBatchProgressLabel)
        txtBatchPercent = findViewById(R.id.txtBatchPercent)
        pbBatchProgress = findViewById(R.id.pbBatchProgress)

        // 初始化汇总栏
        layStatsSummary = findViewById(R.id.layStatsSummary)
        txtSummarySavings = findViewById(R.id.txtSummarySavings)

        // 初始化滑块
        txtQualityVal = findViewById(R.id.txtQualityVal)
        txtScaleVal = findViewById(R.id.txtScaleVal)
        sbQuality = findViewById(R.id.sbQuality)
        sbScale = findViewById(R.id.sbScale)
        
        // 绑定预设方案按钮
        btnPresetUltra = findViewById(R.id.btnPresetUltra)
        btnPresetHigh = findViewById(R.id.btnPresetHigh)
        btnPresetStandard = findViewById(R.id.btnPresetStandard)
        btnPresetMax = findViewById(R.id.btnPresetMax)
        
        // EXIF 开关
        swKeepExif = findViewById(R.id.swKeepExif)

        // 按钮
        btnSelect = findViewById(R.id.btnSelect)
        btnClear = findViewById(R.id.btnClear)
        btnCompress = findViewById(R.id.btnCompress)
        btnSaveToGallery = findViewById(R.id.btnSaveToGallery)

        // 配置列表与点击监听 (点击已完成项弹窗对比)
        rvImages.layoutManager = GridLayoutManager(this, 3)
        adapter = ImageAdapter(this, imageList, 
            onDeleteClickListener = { deletedPosition ->
                if (deletedPosition in 0 until imageList.size) {
                    // 清理临时文件
                    imageList[deletedPosition].tempCompressedFile?.delete()
                    imageList.removeAt(deletedPosition)
                    adapter.notifyItemRemoved(deletedPosition)
                    adapter.notifyItemRangeChanged(deletedPosition, imageList.size)
                    updateUIState()
                    updateSummaryPanel()
                }
            },
            onItemClickListener = { clickedPosition ->
                if (clickedPosition in 0 until imageList.size) {
                    val item = imageList[clickedPosition]
                    if (item.status == 2) {
                        showCompareDialog(item)
                    } else {
                        Toast.makeText(this, "请先点击下方的“⚡ 开始批量压缩”按钮进行处理", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        rvImages.adapter = adapter

        // 滑块改变监听
        sbQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtQualityVal.text = "$progress%"
                resetCompressionState()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val safeProgress = if (progress < 10) 10 else progress
                txtScaleVal.text = "$safeProgress%"
                resetCompressionState()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 预设按钮事件绑定
        btnPresetUltra.setOnClickListener { applyPreset(90, 100) }
        btnPresetHigh.setOnClickListener { applyPreset(80, 80) }
        btnPresetStandard.setOnClickListener { applyPreset(70, 60) }
        btnPresetMax.setOnClickListener { applyPreset(50, 50) }

        // 选择照片点击
        btnSelect.setOnClickListener {
            try {
                pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    pickMediaLegacy.launch(intent)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    try {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        pickMediaLegacy.launch(Intent.createChooser(intent, "选择多张照片"))
                    } catch (e3: Exception) {
                        Toast.makeText(this, "无法拉起系统相册选择器: ${e3.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnClear.setOnClickListener {
            if (!isProcessing) {
                clearTempFiles()
                imageList.clear()
                adapter.notifyDataSetChanged()
                updateUIState()
                layBatchProgress.visibility = View.GONE
                layStatsSummary.visibility = View.GONE
                isCompressed = false
            }
        }

        // 绑定两个分开的工作流按钮
        btnCompress.setOnClickListener {
            startBatchCompression()
        }

        btnSaveToGallery.setOnClickListener {
            saveAllToGallery()
        }
    }

    private fun applyPreset(quality: Int, scale: Int) {
        sbQuality.progress = quality
        sbScale.progress = scale
        txtQualityVal.text = "$quality%"
        txtScaleVal.text = "$scale%"
        resetCompressionState()
        Toast.makeText(this, "预设载入成功", Toast.LENGTH_SHORT).show()
    }

    private fun resetCompressionState() {
        if (isCompressed) {
            // 如果参数改变，重置上一轮的压缩结果，让用户能重新开始压缩
            isCompressed = false
            btnSaveToGallery.isEnabled = false
            btnSaveToGallery.backgroundTintList = getColorStateList(android.R.color.darker_gray)
            layStatsSummary.visibility = View.GONE
            for (item in imageList) {
                item.status = 0
                item.compressedSize = 0
                item.tempCompressedFile?.delete()
                item.tempCompressedFile = null
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun addSelectedImages(uris: List<Uri>) {
        var loadedCount = 0
        for (uri in uris) {
            if (imageList.any { it.uri == uri }) continue
            val originalSize = getUriSize(uri)
            if (originalSize > 0) {
                imageList.add(CompressedImage(uri, originalSize))
                loadedCount++
            }
        }
        if (loadedCount > 0) {
            resetCompressionState()
            adapter.notifyDataSetChanged()
            updateUIState()
            Toast.makeText(this, "载入 $loadedCount 张照片", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUIState() {
        if (imageList.isEmpty()) {
            layEmptyState.visibility = View.VISIBLE
            rvImages.visibility = View.GONE
            btnClear.visibility = View.GONE
            btnCompress.isEnabled = false
            btnCompress.backgroundTintList = getColorStateList(android.R.color.darker_gray)
            btnSaveToGallery.isEnabled = false
            btnSaveToGallery.backgroundTintList = getColorStateList(android.R.color.darker_gray)
        } else {
            layEmptyState.visibility = View.GONE
            rvImages.visibility = View.VISIBLE
            btnClear.visibility = View.VISIBLE
            btnCompress.isEnabled = true
            btnCompress.backgroundTintList = getColorStateList(android.R.color.holo_orange_dark)
            
            // 只有全部成功压缩过，才可以保存
            val allDone = imageList.isNotEmpty() && imageList.all { it.status == 2 }
            if (allDone && isCompressed) {
                btnSaveToGallery.isEnabled = true
                btnSaveToGallery.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
            } else {
                btnSaveToGallery.isEnabled = false
                btnSaveToGallery.backgroundTintList = getColorStateList(android.R.color.darker_gray)
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        sbQuality.isEnabled = enabled
        sbScale.isEnabled = enabled
        btnSelect.isEnabled = enabled
        btnClear.isEnabled = enabled
        btnCompress.isEnabled = enabled
        btnPresetUltra.isEnabled = enabled
        btnPresetHigh.isEnabled = enabled
        btnPresetStandard.isEnabled = enabled
        btnPresetMax.isEnabled = enabled
        swKeepExif.isEnabled = enabled
    }

    /**
     * 阶段 1：批量压缩 (不写入系统相册，只写入本地临时缓存目录并预览对比)
     */
    private fun startBatchCompression() {
        if (imageList.isEmpty() || isProcessing) return

        isProcessing = true
        setControlsEnabled(false)
        layBatchProgress.visibility = View.VISIBLE
        pbBatchProgress.progress = 0
        pbBatchProgress.max = imageList.size
        txtBatchProgressLabel.text = "正在批量压缩 (0/${imageList.size})..."
        txtBatchPercent.text = "0%"
        layStatsSummary.visibility = View.GONE

        val quality = sbQuality.progress
        val rawScale = sbScale.progress
        val scale = (if (rawScale < 10) 10 else rawScale) / 100.0
        val keepExif = swKeepExif.isChecked

        Thread {
            for (i in 0 until imageList.size) {
                val item = imageList[i]
                item.status = 1 // 压缩中
                runOnUiThread { adapter.notifyItemChanged(i) }

                // 核心压缩
                val success = compressSingleTemp(item, quality, scale, keepExif)
                item.status = if (success) 2 else 3

                val currentProgress = i + 1
                val percent = (currentProgress.toFloat() / imageList.size.toFloat() * 100).toInt()

                runOnUiThread {
                    adapter.notifyItemChanged(i)
                    pbBatchProgress.progress = currentProgress
                    txtBatchProgressLabel.text = "正在批量压缩 ($currentProgress/${imageList.size})..."
                    txtBatchPercent.text = "$percent%"
                }
            }

            runOnUiThread {
                isProcessing = false
                isCompressed = true
                setControlsEnabled(true)
                updateUIState()
                updateSummaryPanel()
                Toast.makeText(this, "压缩完成！点击图片项可查看左右原图对比", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun compressSingleTemp(item: CompressedImage, quality: Int, scale: Double, keepExif: Boolean): Boolean {
        var originalBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        try {
            val originalStream = contentResolver.openInputStream(item.uri) ?: return false
            originalBitmap = BitmapFactory.decodeStream(originalStream)
            originalStream.close()

            if (originalBitmap == null) {
                item.errorMsg = "原图解码失败"
                return false
            }

            val targetWidth = (originalBitmap.width * scale).toInt()
            val targetHeight = (originalBitmap.height * scale).toInt()
            scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)

            // 写入本地应用私有临时文件
            val tempFile = File(cacheDir, "temp_compressed_${System.currentTimeMillis()}_${(100..999).random()}.jpg")
            val outputStream = FileOutputStream(tempFile)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            outputStream.flush()
            outputStream.close()

            // 如果开启 EXIF，将元数据无损复制入临时文件中
            if (keepExif) {
                copyExifData(item.uri, tempFile)
            }

            item.tempCompressedFile = tempFile
            item.compressedSize = tempFile.length()
            return true
        } catch (e: Throwable) {
            e.printStackTrace()
            item.errorMsg = e.message
            return false
        } finally {
            originalBitmap?.recycle()
            scaledBitmap?.recycle()
        }
    }

    /**
     * 更新保存前的压缩报告汇总
     */
    private fun updateSummaryPanel() {
        val allDone = imageList.isNotEmpty() && imageList.all { it.status == 2 }
        if (allDone && isCompressed) {
            var totalOrg = 0L
            var totalComp = 0L
            for (item in imageList) {
                totalOrg += item.originalSize
                totalComp += item.compressedSize
            }
            val savings = ((1 - totalComp.toFloat() / totalOrg.toFloat()) * 100).toInt()
            txtSummarySavings.text = "原始总大小: ${formatFileSize(totalOrg)} -> 压缩后: ${formatFileSize(totalComp)} (已节省 $savings%)"
            layStatsSummary.visibility = View.VISIBLE
        } else {
            layStatsSummary.visibility = View.GONE
        }
    }

    /**
     * 阶段 2：批量保存到相册 (在这一阶段，提取时间戳并精准写入系统 MediaStore，保证相册中排在昨天的日期里)
     */
    private fun saveAllToGallery() {
        val successItems = imageList.filter { it.status == 2 && it.tempCompressedFile != null }
        if (successItems.isEmpty() || isProcessing) return

        isProcessing = true
        setControlsEnabled(false)
        btnSaveToGallery.isEnabled = false
        btnSaveToGallery.text = "正在保存到系统相册..."
        
        val keepExif = swKeepExif.isChecked

        Thread {
            var savedCount = 0
            for (item in successItems) {
                val file = item.tempCompressedFile ?: continue
                if (!file.exists()) continue

                // 核心：确定原始拍摄时间毫秒值，如果无则降级
                var dateTakenMillis = System.currentTimeMillis()
                if (keepExif) {
                    var exifStream: InputStream? = null
                    try {
                        exifStream = contentResolver.openInputStream(item.uri)
                        if (exifStream != null) {
                            val exif = ExifInterface(exifStream)
                            val dateTimeStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                                              ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                            if (!dateTimeStr.isNullOrEmpty()) {
                                dateTakenMillis = parseExifDateTime(dateTimeStr)
                            } else {
                                // 尝试从 MediaStore 获取原图的拍摄时间
                                dateTakenMillis = getMediaStoreDateTaken(item.uri)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        exifStream?.close()
                    }
                }

                // 写入系统相册 (携带精确的拍摄时间，相册将精准排在原始拍摄的日期位置)
                val displayName = "compressed_${System.currentTimeMillis()}_${(100..999).random()}.jpg"
                val savedUri = saveToGallery(file, displayName, dateTakenMillis)
                if (savedUri != null) {
                    savedCount++
                }
            }

            runOnUiThread {
                isProcessing = false
                updateUIState()
                btnSaveToGallery.text = "💾 保存全部到相册"
                Toast.makeText(this, "成功保存 $savedCount 张照片至系统相册 (已精准同步原始拍摄时间)！", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun parseExifDateTime(dateTimeStr: String?): Long {
        if (dateTimeStr.isNullOrEmpty()) return System.currentTimeMillis()
        try {
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            return sdf.parse(dateTimeStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            e.printStackTrace()
            return System.currentTimeMillis()
        }
    }

    private fun getMediaStoreDateTaken(uri: Uri): Long {
        val projection = arrayOf(MediaStore.Images.Media.DATE_TAKEN)
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    if (columnIndex != -1) {
                        val dateTaken = cursor.getLong(columnIndex)
                        if (dateTaken > 0) return dateTaken
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return System.currentTimeMillis()
    }

    private fun saveToGallery(file: File, displayName: String, dateTakenMillis: Long): Uri? {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // 写入拍摄和添加时间 (毫秒及秒)
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMillis)
            put(MediaStore.Images.Media.DATE_ADDED, dateTakenMillis / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, dateTakenMillis / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (imageUri != null) {
            try {
                resolver.openOutputStream(imageUri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }
                return imageUri
            } catch (e: Exception) {
                e.printStackTrace()
                resolver.delete(imageUri, null, null)
            }
        }
        return null
    }

    /**
     * 弹窗对比：左右双列对比大图，限制最大分辨率以防 OOM
     */
    private fun showCompareDialog(item: CompressedImage) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_compare)

        val txtDialogTitle = dialog.findViewById<TextView>(R.id.txtDialogTitle)
        val txtDialogOrgLabel = dialog.findViewById<TextView>(R.id.txtDialogOrgLabel)
        val txtDialogCompLabel = dialog.findViewById<TextView>(R.id.txtDialogCompLabel)
        val imgDialogOriginal = dialog.findViewById<ImageView>(R.id.imgDialogOriginal)
        val imgDialogCompressed = dialog.findViewById<ImageView>(R.id.imgDialogCompressed)
        val txtDialogSummary = dialog.findViewById<TextView>(R.id.txtDialogSummary)
        val btnDialogClose = dialog.findViewById<Button>(R.id.btnDialogClose)

        val fileName = getFileName(item.uri)
        txtDialogTitle.text = "对比预览: $fileName"

        val orgSizeStr = formatFileSize(item.originalSize)
        val compSizeStr = formatFileSize(item.compressedSize)
        val savings = ((1 - item.compressedSize.toFloat() / item.originalSize.toFloat()) * 100).toInt()
        
        txtDialogSummary.text = "原始容量: $orgSizeStr -> 压缩后: $compSizeStr (节省了 $savings%)"
        txtDialogOrgLabel.text = "原图预览 ($orgSizeStr)"
        txtDialogCompLabel.text = "压缩后预览 ($compSizeStr)"

        // 采样率下加载原图与压缩图 (800 宽防止 OOM)
        val orgBitmap = loadScaledBitmap(item.uri, 800)
        if (orgBitmap != null) {
            imgDialogOriginal.setImageBitmap(orgBitmap)
        } else {
            imgDialogOriginal.setImageURI(item.uri)
        }

        val compFile = item.tempCompressedFile
        if (compFile != null && compFile.exists()) {
            val compBitmap = loadScaledBitmap(Uri.fromFile(compFile), 800)
            if (compBitmap != null) {
                imgDialogCompressed.setImageBitmap(compBitmap)
            } else {
                imgDialogCompressed.setImageURI(Uri.fromFile(compFile))
            }
        }

        btnDialogClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "photo.jpg"
    }

    private fun loadScaledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        var inputStream: InputStream? = null
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            inputStream = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            val srcWidth = options.outWidth
            val srcHeight = options.outHeight
            var inSampleSize = 1
            if (srcWidth > maxDimension || srcHeight > maxDimension) {
                val halfWidth = srcWidth / 2
                val halfHeight = srcHeight / 2
                while ((halfWidth / inSampleSize) >= maxDimension && (halfHeight / inSampleSize) >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val finalOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                this.inSampleSize = inSampleSize
            }
            inputStream = contentResolver.openInputStream(uri)
            return BitmapFactory.decodeStream(inputStream, null, finalOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            inputStream?.close()
        }
    }

    private fun clearTempFiles() {
        for (item in imageList) {
            item.tempCompressedFile?.delete()
        }
    }

    private fun getUriSize(uri: Uri): Long {
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length
            } ?: 0L
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.00").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    private fun copyExifData(sourceUri: Uri, destFile: File) {
        var sourceStream: InputStream? = null
        try {
            sourceStream = contentResolver.openInputStream(sourceUri)
            if (sourceStream != null) {
                val sourceExif = ExifInterface(sourceStream)
                val destExif = ExifInterface(destFile.absolutePath)

                val tagsToCopy = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_SUBSEC_TIME,
                    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_ISO_SPEED_RATINGS
                )

                for (tag in tagsToCopy) {
                    val value = sourceExif.getAttribute(tag)
                    if (value != null) {
                        destExif.setAttribute(tag, value)
                    }
                }
                destExif.saveAttributes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            sourceStream?.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTempFiles()
    }
}
