package com.cca.imagecompression

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.InputStream
import java.text.DecimalFormat

data class CompressedImage(
    val uri: Uri,
    val originalSize: Long,
    var compressedSize: Long = 0,
    var status: Int = 0, // 0 = 等待中, 1 = 压缩中, 2 = 成功, 3 = 失败
    var errorMsg: String? = null,
    var tempCompressedFile: File? = null // 保存本地压缩后的临时文件引用
)

class ImageAdapter(
    private val context: Context,
    private val imageList: ArrayList<CompressedImage>,
    private val onDeleteClickListener: (Int) -> Unit,
    private val onItemClickListener: (Int) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgItemPreview: ImageView = view.findViewById(R.id.imgItemPreview)
        val txtItemOrgSize: TextView = view.findViewById(R.id.txtItemOrgSize)
        val btnItemDelete: ImageButton = view.findViewById(R.id.btnItemDelete)
        val progressItem: ProgressBar = view.findViewById(R.id.progressItem)
        val txtItemStatusText: TextView = view.findViewById(R.id.txtItemStatusText)
        val imgItemSuccess: ImageView = view.findViewById(R.id.imgItemSuccess)
        val layItemStatus: LinearLayout = view.findViewById(R.id.layItemStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = imageList[position]

        // 1. 显示原始大小
        holder.txtItemOrgSize.text = formatFileSize(item.originalSize)

        // 2. 绑定删除事件
        holder.btnItemDelete.setOnClickListener {
            onDeleteClickListener(holder.adapterPosition)
        }

        // 3. 绑定整个 Item 的点击事件 (对比预览)
        holder.itemView.setOnClickListener {
            onItemClickListener(holder.adapterPosition)
        }

        // 4. 根据状态更新 UI 样式
        when (item.status) {
            0 -> { // 等待中
                holder.progressItem.visibility = View.GONE
                holder.imgItemSuccess.visibility = View.GONE
                holder.txtItemStatusText.text = "等待中"
                holder.txtItemStatusText.setTextColor(0xFFD70000.toInt().inv()) // 金黄色
                holder.layItemStatus.setBackgroundColor(0xCC000000.toInt())
                holder.btnItemDelete.visibility = View.VISIBLE
            }
            1 -> { // 压缩中
                holder.progressItem.visibility = View.VISIBLE
                holder.imgItemSuccess.visibility = View.GONE
                holder.txtItemStatusText.text = "压缩中"
                holder.txtItemStatusText.setTextColor(0xFFD70000.toInt().inv()) // 金黄色
                holder.layItemStatus.setBackgroundColor(0xCC000000.toInt())
                holder.btnItemDelete.visibility = View.GONE // 正在处理时不允许删除
            }
            2 -> { // 成功
                holder.progressItem.visibility = View.GONE
                holder.imgItemSuccess.visibility = View.VISIBLE
                holder.txtItemStatusText.text = formatFileSize(item.compressedSize)
                holder.txtItemStatusText.setTextColor(0xFF4CAF50.toInt()) // 绿色
                holder.layItemStatus.setBackgroundColor(0xCC081A08.toInt()) // 微绿背景
                holder.btnItemDelete.visibility = View.VISIBLE
            }
            3 -> { // 失败
                holder.progressItem.visibility = View.GONE
                holder.imgItemSuccess.visibility = View.GONE
                holder.txtItemStatusText.text = "失败"
                holder.txtItemStatusText.setTextColor(0xFFFF5555.toInt()) // 红色
                holder.layItemStatus.setBackgroundColor(0xCC200505.toInt()) // 微红背景
                holder.btnItemDelete.visibility = View.VISIBLE
            }
        }

        // 5. 安全解码加载预览图，避免 OOM
        try {
            val previewBitmap = loadScaledBitmap(item.uri, 200)
            if (previewBitmap != null) {
                holder.imgItemPreview.setImageBitmap(previewBitmap)
            } else {
                holder.imgItemPreview.setImageResource(android.graphics.drawable.ClipDrawable.HORIZONTAL)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            holder.imgItemPreview.setImageResource(android.graphics.drawable.ClipDrawable.HORIZONTAL)
        }
    }

    override fun getItemCount(): Int = imageList.size

    private fun loadScaledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        var inputStream: InputStream? = null
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            inputStream = context.contentResolver.openInputStream(uri)
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
            inputStream = context.contentResolver.openInputStream(uri)
            return BitmapFactory.decodeStream(inputStream, null, finalOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            inputStream?.close()
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.00").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
