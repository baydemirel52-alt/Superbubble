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
            setPadding(55, 55, 55, 55)
        }

        val title = TextView(this).apply {
            text = "SuperBubble Messenger Pro"
            textSize = 21f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 25)
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 35)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Ekran Üstü Balon İzni"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    Toast.makeText(this@MainActivity, "Bu izin zaten aktif kanka!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnNotification = Button(this).apply {
            text = "2. Bildirim & Arama Yakalama İzni"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val btnMediaAndContacts = Button(this).apply {
            text = "3. Fotoğraf & Sesli/Görüntülü Arama İzni"
            setOnClickListener {
                requestRuntimePermissions()
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "4. WhatsApp İçi Geçmiş Eşitleme İzni"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "İndirilen Uygulamalar > SuperBubble'ı açıp aktif et kanka",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        layout.addView(btnMediaAndContacts)
        layout.addView(btnAccessibility)
        layout.addView(btnTest)

        setContentView(layout)
    }

    private fun hasRuntimePermissions(): Boolean {
        val hasPhoto = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return hasPhoto && hasContacts && hasCall
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 102)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotif = isNotificationServiceEnabled()
        val hasPerms = hasRuntimePermissions()
        val hasSync = isAccessibilityServiceEnabled()

        statusText.text =
            "1. Ekran Üstü Balon: ${if (hasOverlay) "AKTİF ✅" else "EKSİK ❌"}\n" +
            "2. Bildirim & Çağrı Yakalama: ${if (hasNotif) "AKTİF ✅" else "EKSİK ❌"}\n" +
            "3. Fotoğraf & Doğrudan Arama: ${if (hasPerms) "AKTİF ✅" else "EKSİK ❌"}\n" +
            "4. Uygulama İçi Sohbet Eşitleme: ${if (hasSync) "AKTİF ✅" else "İSTEĞE BAĞLI ⚡"}\n\n" +
            "Not: 4. izin kısıtlı görünürse Ayarlar > Uygulamalar > SuperBubble > Sağ üst 3 nokta > Kısıtlanmış ayarlara izin ver demen yeterli."
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, BubbleService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val cn = ComponentName(this, ChatSyncService::class.java)
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return flat != null && flat.contains(cn.flattenToString())
    }
}
