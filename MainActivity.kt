package com.smplan.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4103)
        }
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try { startActivityForResult(intent, filePickerRequest); true }
                catch (_: Exception) { fileCallback = null; false }
            }
        }
        webView.addJavascriptInterface(SmPlanBridge(this), "SmPlanNative")
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    @Deprecated("Deprecated in Android API, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == filePickerRequest) {
            val result = if (resultCode == RESULT_OK && data != null) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        }
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("(function(){var m=[...document.querySelectorAll('.full-modal.active')];if(m.length){m[m.length-1].classList.remove('active');return 'modal';}var d=document.querySelector('.custom-dialog-overlay.active');if(d){d.classList.remove('active');return 'dialog';}var p=document.getElementById('popupWindow');if(p&&p.classList.contains('active')){p.classList.remove('active');return 'popup';}return 'exit';})()") { result ->
            if (result == "\"exit\"") finish()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("smplan_reminders", "تذكيرات SmPlan", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "تنبيهات مواعيد المهام في SmPlan"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    class SmPlanBridge(private val activity: Activity) {
        @JavascriptInterface
        fun scheduleNotification(delayMsText: String, title: String, body: String, taskId: String) {
            val delay = delayMsText.toLongOrNull() ?: return
            if (delay <= 0) return
            val intent = Intent(activity, ReminderReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("body", body)
                putExtra("taskId", taskId)
            }
            val id = taskId.hashCode()
            val pending = PendingIntent.getBroadcast(activity, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val alarm = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pending)
        }

        @JavascriptInterface
        fun saveBackup(json: String, filename: String) {
            try {
                val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "SmPlan_Backup.json" }
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SmPlan")
                    }
                    val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("تعذر إنشاء ملف النسخة")
                    activity.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException("تعذر كتابة ملف النسخة")
                    activity.runOnUiThread { Toast.makeText(activity, "تم حفظ النسخة في Downloads/SmPlan", Toast.LENGTH_LONG).show() }
                } else {
                    val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SmPlan").apply { mkdirs() }
                    File(dir, safeName).writeText(json, Charsets.UTF_8)
                    activity.runOnUiThread { Toast.makeText(activity, "تم حفظ النسخة داخل مجلد Downloads/SmPlan الخاص بالتطبيق", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "فشل حفظ النسخة: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }

        @JavascriptInterface
        fun appVersion(): String = "2.0.0"
    }
}

class ReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = intent.getStringExtra("title") ?: "تذكير من SmPlan"
        val body = intent.getStringExtra("body") ?: "حان موعد مهمتك"
        val id = (intent.getStringExtra("taskId") ?: title).hashCode()
        val notification = NotificationCompat.Builder(context, "smplan_reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(id, notification)
    }
}
