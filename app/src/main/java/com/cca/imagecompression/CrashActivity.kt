package com.cca.imagecompression

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)

        val crashLog = intent.getStringExtra("error") ?: "未知异常信息"
        
        val txtCrashLog = findViewById<TextView>(R.id.txtCrashLog)
        val btnCopyCrash = findViewById<Button>(R.id.btnCopyCrash)
        val btnRestartApp = findViewById<Button>(R.id.btnRestartApp)

        txtCrashLog.text = crashLog

        btnCopyCrash.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Crash Log", crashLog)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "崩溃日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        btnRestartApp.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
    }
}
