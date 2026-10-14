package com.selim.superbubble

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "SuperBubble Pro Kontrol Paneli"
            textSize = 21f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 30)
        }

        statusText = TextView(this).apply {
            textSize = 14.5f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 40)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Ekran Üstü Balon İznini Ver"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    Toast.makeText(this@MainActivity, "Ekran üstü izni zaten aktif kanka!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnNotification = Button(this).apply {
            text = "2. Bildirim Yakalama İznini Ver"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val btnMedia = Button(this).apply {
            text = "3. Fotoğraf Görme ve Gönderme İzni"
            setOnClickListener {
                requestPhotoPermission()
            }
        }

        val btnTest = Button(this).apply {
            text = "Test Balonu Gönder"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    BubbleService.spawnTestBubble(this@MainActivity)
                } else {
                    Toast.makeText(this@MainActivity, "Önce 1. izni vermelisin kanka!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(btnOverlay)
        layout.addView(btnNotification)
        layout.addView(btnMedia)
        layout.addView(btnTest)

        setContentView(layout)
    }

    private fun hasPhotoPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPhotoPermission() {
        if (hasPhotoPermission()) {
            Toast.makeText(this, "Fotoğraf izni zaten aktif kanka!", Toast.LENGTH_SHORT).show()
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotif = isNotificationServiceEnabled()
        val hasPhoto = hasPhotoPermission()
        statusText.text =
            "1. Ekran Üstü İzni: ${if (hasOverlay) "AKTİF ✅" else "EKSİK ❌"}\n" +
            "2. Bildirim İzni: ${if (hasNotif) "AKTİF ✅" else "EKSİK ❌"}\n" +
            "3. Fotoğraf/Galeri İzni: ${if (hasPhoto) "AKTİF ✅" else "EKSİK ❌"}\n\n" +
            "Üç izin de aktif olduğunda fotoğraflar, emojiler ve yatay oyun modu tam kapasite çalışır."
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, BubbleService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }
}
