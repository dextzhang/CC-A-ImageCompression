package com.cca.imagecompression

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var imgOriginal: ImageView
    private lateinit var imgCompressed: ImageView
    private lateinit var txtOriginalSize: TextView
    private lateinit var txtCompressedSize: TextView
    private lateinit var txtQualityVal: TextView
    private lateinit var txtScaleVal: TextView
    private lateinit var sbQuality: SeekBar
    private lateinit var sbScale: SeekBar
    private lateinit var btnSelect: Button
    private lateinit var btnCompressSave: Button

    private var selectedImageUri: Uri? = null
    private var compressedTempFile: File? = null

    // 使用现代的 PhotoPicker 注册选择器，无需申请任何读取存储的动态权限
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            imgOriginal.setImageURI(uri)
            
            // 获取并显示原图大小
            val originalSize = getUriSize(uri)
            txtOriginalSize.text = formatFileSize(originalSize)
            
            // 重置压缩图显示
            imgCompressed.setImageResource(android.graphics.drawable.ClipDrawable.HORIZONTAL)
            txtCompressedSize.text = "-- MB"
            btnCompressSave.isEnabled = true
            
            Toast.makeText(this, "图片载入成功", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 UI 控件
        imgOriginal = findViewById(R.id.imgOriginal)
        imgCompressed = findViewById(R.id.imgCompressed)
        txtOriginalSize = findViewById(R.id.txtOriginalSize)
        txtCompressedSize = findViewById(R.id.txtCompressedSize)
        txtQualityVal = findViewById(R.id.txtQualityVal)
        txtScaleVal = findViewById(R.id.txtScaleVal)
        sbQuality = findViewById(R.id.sbQuality)
        sbScale = findViewById(R.id.sbScale)
        btnSelect = findViewById(R.id.btnSelect)
        btnCompressSave = findViewById(R.id.btnCompressSave)

        // 监听滑块
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

        // 绑定按钮事件
        btnSelect.setOnClickListener {
            // 只选择图片
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        btnCompressSave.setOnClickListener {
            val uri = selectedImageUri
            if (uri == null) {
                Toast.makeText(this, "请先选择一张照片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 执行压缩与保存
            compressAndSaveImage(uri)
        }
    }

    private fun compressAndSaveImage(uri: Uri) {
        try {
            val quality = sbQuality.progress
            val rawScale = sbScale.progress
            val scale = (if (rawScale < 10) 10 else rawScale) / 100.0

            // 1. 从 Uri 解码原始图片
            val originalStream = contentResolver.openInputStream(uri) ?: throw Exception("无法读取原图数据")
            val originalBitmap = BitmapFactory.decodeStream(originalStream)
            originalStream.close()

            // 2. 根据比例进行缩放
            val targetWidth = (originalBitmap.width * scale).toInt()
            val targetHeight = (originalBitmap.height * scale).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)

            // 3. 压缩写入临时文件
            val tempFile = File(cacheDir, "temp_compressed.jpg")
            val outputStream = FileOutputStream(tempFile)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            outputStream.flush()
            outputStream.close()

            // 4. 关键点：无损拷贝 EXIF 元数据 (包含拍摄时间、相机型号、GPS等信息)
            copyExifData(uri, tempFile)

            // 5. 显示压缩后的预览与大小
            imgCompressed.setImageURI(Uri.fromFile(tempFile))
            txtCompressedSize.text = formatFileSize(tempFile.length())

            // 6. 使用 MediaStore 免权限保存至系统 Pictures 公共相册
            val savedUri = saveToGallery(tempFile, "compressed_${System.currentTimeMillis()}.jpg")
            
            if (savedUri != null) {
                Toast.makeText(this, "已成功保存至系统相册 Pictures 目录", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "保存到相册失败", Toast.LENGTH_SHORT).show()
            }

            // 释放内存
            if (originalBitmap != scaledBitmap) {
                originalBitmap.recycle()
            }
            scaledBitmap.recycle()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "压缩失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 提取并拷贝完整的 EXIF 元数据 (特别是原始拍摄时间) 写入到压缩后的临时图片中
     */
    private fun copyExifData(sourceUri: Uri, destFile: File) {
        var sourceStream: InputStream? = null
        try {
            sourceStream = contentResolver.openInputStream(sourceUri)
            if (sourceStream != null) {
                val sourceExif = ExifInterface(sourceStream)
                val destExif = ExifInterface(destFile.absolutePath)

                // 需要完美保留的核心 EXIF 信息标签列表 (包括时间戳、GPS经纬度、相机厂商型号、屏幕朝向等)
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
                // 保存修改后的 Exif 属性
                destExif.saveAttributes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果 EXIF 复制失败，打印日志，不中断应用主流程
        } finally {
            sourceStream?.close()
        }
    }

    /**
     * 通过 MediaStore 将压缩加固后的文件安全地存入系统公共相册中 (Android 10+ 免申请写入权限)
     */
    private fun saveToGallery(file: File, displayName: String): Uri? {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // 写入系统公用的 Pictures 存储根目录下
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        
        if (imageUri != null) {
            try {
                // 将临时压缩好的、带 EXIF 的图片复制到相册的 URI 中
                resolver.openOutputStream(imageUri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                // 标记已完成写入，更新为图库可见
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }
                return imageUri
            } catch (e: Exception) {
                e.printStackTrace()
                // 出现异常时清除无效的 Media 记录
                resolver.delete(imageUri, null, null)
            }
        }
        return null
    }

    private fun getUriSize(uri: Uri): Long {
        return contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            it.length
        } ?: 0L
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.00").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
