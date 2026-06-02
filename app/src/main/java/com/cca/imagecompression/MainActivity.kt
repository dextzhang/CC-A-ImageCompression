package com.cca.imagecompression

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var layEmptyState: LinearLayout
    private lateinit var rvImages: RecyclerView
    private lateinit var layBatchProgress: LinearLayout
    private lateinit var txtBatchProgressLabel: TextView
    private lateinit var txtBatchPercent: TextView
    private lateinit var pbBatchProgress: ProgressBar

    private lateinit var txtQualityVal: TextView
    private lateinit var txtScaleVal: TextView
    private lateinit var sbQuality: SeekBar
    private lateinit var sbScale: SeekBar
    private lateinit var btnSelect: Button
    private lateinit var btnClear: Button
    private lateinit var btnCompressSave: Button

    private val imageList = ArrayList<CompressedImage>()
    private lateinit var adapter: ImageAdapter
    private var isProcessing = false

    // 现代多选 PhotoPicker 注册选择器
    private val pickMultipleMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            addSelectedImages(uris)
        }
    }

    // 传统多选相册降级选择器，用作部分定制 ROM 上的崩溃降级
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

        // 配置全局未捕获异常拦截器，跳转到 CrashActivity 优雅展示
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

        // 初始化主工作区布局
        layEmptyState = findViewById(R.id.layEmptyState)
        rvImages = findViewById(R.id.rvImages)
        layBatchProgress = findViewById(R.id.layBatchProgress)
        txtBatchProgressLabel = findViewById(R.id.txtBatchProgressLabel)
        txtBatchPercent = findViewById(R.id.txtBatchPercent)
        pbBatchProgress = findViewById(R.id.pbBatchProgress)

        // 初始化滑块与控制按钮
        txtQualityVal = findViewById(R.id.txtQualityVal)
        txtScaleVal = findViewById(R.id.txtScaleVal)
        sbQuality = findViewById(R.id.sbQuality)
        sbScale = findViewById(R.id.sbScale)
        btnSelect = findViewById(R.id.btnSelect)
        btnClear = findViewById(R.id.btnClear)
        btnCompressSave = findViewById(R.id.btnCompressSave)

        // 配置网格列表
        rvImages.layoutManager = GridLayoutManager(this, 3)
        adapter = ImageAdapter(this, imageList) { deletedPosition ->
            // 删除回调逻辑
            if (deletedPosition in 0 until imageList.size) {
                imageList.removeAt(deletedPosition)
                adapter.notifyItemRemoved(deletedPosition)
                adapter.notifyItemRangeChanged(deletedPosition, imageList.size)
                updateUIState()
            }
        }
        rvImages.adapter = adapter

        // 监听滑块修改
        sbQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtQualityVal.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val safeProgress = if (progress < 10) 10 else progress
                txtScaleVal.text = "$safeProgress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 按钮点击事件
        btnSelect.setOnClickListener {
            try {
                // 1. 尝试使用现代 PhotoPicker 多选模式
                pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: Exception) {
                e.printStackTrace()
                // 2. 第一重防崩溃降级：拉起支持多选的传统 Action
                try {
                    val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    pickMediaLegacy.launch(intent)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    // 3. 第二重降级：采用广泛多选文件选择 Intent
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
                imageList.clear()
                adapter.notifyDataSetChanged()
                updateUIState()
                layBatchProgress.visibility = View.GONE
            }
        }

        btnCompressSave.setOnClickListener {
            startBatchCompression()
        }
    }

    /**
     * 将导入的 Uri 集合，安全地封装并添加至网格队列中
     */
    private fun addSelectedImages(uris: List<Uri>) {
        var loadedCount = 0
        for (uri in uris) {
            // 防止重复添加
            if (imageList.any { it.uri == uri }) continue

            val originalSize = getUriSize(uri)
            if (originalSize > 0) {
                imageList.add(CompressedImage(uri, originalSize))
                loadedCount++
            }
        }
        if (loadedCount > 0) {
            adapter.notifyDataSetChanged()
            updateUIState()
            Toast.makeText(this, "成功载入 $loadedCount 张照片", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 根据当前队列元素个数，切换工作区空状态、清空与开始按钮的启用状态
     */
    private fun updateUIState() {
        if (imageList.isEmpty()) {
            layEmptyState.visibility = View.VISIBLE
            rvImages.visibility = View.GONE
            btnClear.visibility = View.GONE
            btnCompressSave.isEnabled = false
            btnCompressSave.backgroundTintList = getColorStateList(android.R.color.darker_gray)
        } else {
            layEmptyState.visibility = View.GONE
            rvImages.visibility = View.VISIBLE
            btnClear.visibility = View.VISIBLE
            btnCompressSave.isEnabled = true
            btnCompressSave.backgroundTintList = getColorStateList(android.R.color.holo_orange_dark) // 亮色
        }
    }

    /**
     * 禁用或开启界面参数控制（压缩过程中锁死控制组件以防止误操作）
     */
    private fun setControlsEnabled(enabled: Boolean) {
        sbQuality.isEnabled = enabled
        sbScale.isEnabled = enabled
        btnSelect.isEnabled = enabled
        btnClear.isEnabled = enabled
        btnCompressSave.isEnabled = enabled
    }

    /**
     * 多图批量压缩主流程：通过后台子线程平滑处理，解决 ANR 和前台 UI 卡死
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

        val quality = sbQuality.progress
        val rawScale = sbScale.progress
        val scale = (if (rawScale < 10) 10 else rawScale) / 100.0

        // 启动后台线程
        Thread {
            for (i in 0 until imageList.size) {
                val item = imageList[i]
                item.status = 1 // 1=压缩中
                runOnUiThread {
                    adapter.notifyItemChanged(i)
                }

                // 核心单个压缩逻辑
                val success = compressAndSaveSingle(item, quality, scale)
                
                item.status = if (success) 2 else 3 // 2=成功，3=失败

                val currentProgress = i + 1
                val percent = (currentProgress.toFloat() / imageList.size.toFloat() * 100).toInt()

                // 刷新界面进度
                runOnUiThread {
                    adapter.notifyItemChanged(i)
                    pbBatchProgress.progress = currentProgress
                    txtBatchProgressLabel.text = "正在批量压缩 ($currentProgress/${imageList.size})..."
                    txtBatchPercent.text = "$percent%"
                }
            }

            // 全部处理完毕后回到主线程收尾
            runOnUiThread {
                isProcessing = false
                setControlsEnabled(true)
                Toast.makeText(this, "批量压缩已全部完成！照片已保存至 Pictures 目录", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /**
     * 单张图片压缩核心子过程 (附带 EXIF 完美无损拷贝与底层 Bitmap 回收释放)
     */
    private fun compressAndSaveSingle(item: CompressedImage, quality: Int, scale: Double): Boolean {
        var originalBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        var tempFile: File? = null
        try {
            // 1. 从原图 Uri 解码出原始像素
            val originalStream = contentResolver.openInputStream(item.uri) ?: return false
            originalBitmap = BitmapFactory.decodeStream(originalStream)
            originalStream.close()

            if (originalBitmap == null) {
                item.errorMsg = "原图解码失败"
                return false
            }

            // 2. 计算缩放像素尺寸
            val targetWidth = (originalBitmap.width * scale).toInt()
            val targetHeight = (originalBitmap.height * scale).toInt()

            // 3. 执行缩放创建新 Bitmap
            scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)

            // 4. 压缩写入缓存文件
            tempFile = File(cacheDir, "temp_batch_${System.currentTimeMillis()}_${(100..999).random()}.jpg")
            val outputStream = FileOutputStream(tempFile)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            outputStream.flush()
            outputStream.close()

            // 5. 复制并保留 EXIF 原始时间与位置属性
            copyExifData(item.uri, tempFile)

            // 6. 免权限写入系统相册根目录下的 Pictures 文件夹中
            val savedUri = saveToGallery(tempFile, "compressed_${System.currentTimeMillis()}_${(100..999).random()}.jpg")

            if (savedUri != null) {
                item.compressedSize = getUriSize(savedUri)
                return true
            } else {
                item.errorMsg = "系统相册写入失败"
                return false
            }

        } catch (e: Throwable) { // 捕获 Throwable 能抓取 OOM Error，避免闪退
            e.printStackTrace()
            item.errorMsg = e.message
            return false
        } finally {
            // 极其关键的内存物理回收，防止批量任务将手机内存彻底耗尽
            try {
                originalBitmap?.recycle()
                scaledBitmap?.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 保留核心 EXIF 属性
     */
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
            try {
                sourceStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 将临时文件导入系统相册
     */
    private fun saveToGallery(file: File, displayName: String): Uri? {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
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
}
