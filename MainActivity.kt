package com.selim.superbubble

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(45, 45, 45, 65)
        }

        val title = TextView(this).apply {
            text = "SuperBubble Pro v5.0"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }

        statusText = TextView(this).apply {
            textSize = 13.5f
            setTextColor(Color.LTGRAY)
            setPadding(30, 25, 30, 25)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 24f
            }
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
            text = "3. Fotoğraf & Rehber İzni (WhatsApp Arama)"
            setOnClickListener {
                requestRuntimePermissions()
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "4. Uygulama İçi Geçmiş Eşitleme İzni"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "İndirilen Uygulamalar > SuperBubble'ı açıp aktif et kanka",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnBattery = Button(this).apply {
            text = "5. Pil Kısıtlamasını Kaldır (S22 Ultra Kapanma Önleyici)"
            setOnClickListener {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        val btnTest = Button(this).apply {
            text = "🚀 Test Balonu Gönder"
            setOnClickListener {
                BubbleService.spawnTestBubble(this@MainActivity)
            }
        }

        // ==================== AYARLAR BÖLÜMÜ ====================
        val settingsTitle = TextView(this).apply {
            text = "⚙️ Balon & Uygulama Ayarları"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 35, 0, 15)
        }

        val appsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 24f
            }
        }

        val enabledSet = SettingsManager.getEnabledPackages(this)
        val appItems = listOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram"
        )

        appItems.forEach { (pkg, label) ->
            val cb = CheckBox(this).apply {
                text = label
                setTextColor(Color.WHITE)
                isChecked = enabledSet.contains(pkg)
                setOnCheckedChangeListener { _, checked ->
                    SettingsManager.setPackageEnabled(this@MainActivity, pkg, checked)
                    if (pkg == "org.telegram.messenger") {
                        SettingsManager.setPackageEnabled(this@MainActivity, "org.telegram.messenger.web", checked)
                    }
                }
            }
            appsBox.addView(cb)
        }

        // Balon Boyutu Seçimi
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 10)
        }

        listOf(56 to "Küçük Balon", 68 to "Orta Balon", 80 to "Büyük Balon").forEach { (dpVal, label) ->
            val b = Button(this).apply {
                text = label
                textSize = 11.5f
                setOnClickListener {
                    SettingsManager.setBubbleSizeDp(this@MainActivity, dpVal)
                    Toast.makeText(this@MainActivity, "Balon boyutu: $label (bir sonraki balonda geçerli olur)", Toast.LENGTH_SHORT).show()
                }
            }
            sizeRow.addView(b)
        }

        // Kişi Filtresi / Sadece Seçili Kişiler
        val filterInput = EditText(this).apply {
            hint = "Sadece şu kişilerde balon aç (Örn: Mertcan, Ali) - Boşsa herkes"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            setText(SettingsManager.getAllowedSendersCsv(this@MainActivity))
        }

        val btnSaveFilter = Button(this).apply {
            text = "Kişi Filtresini Kaydet"
            setOnClickListener {
                val csv = filterInput.text.toString().trim()
                SettingsManager.setAllowedSendersCsv(this@MainActivity, csv)
                SettingsManager.setFilterMode(this@MainActivity, if (csv.isEmpty()) 0 else 2)
                Toast.makeText(
                    this@MainActivity,
                    if (csv.isEmpty()) "Tüm kişiler aktif edildi kanka!" else "Sadece seçili kişiler aktif: $csv",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(btnOverlay)
        layout.addView(btnNotification)
        layout.addView(btnMediaAndContacts)
        layout.addView(btnAccessibility)
        layout.addView(btnBattery)
        layout.addView(btnTest)
        layout.addView(settingsTitle)
        layout.addView(appsBox)
        layout.addView(sizeRow)
        layout.addView(filterInput)
        layout.addView(btnSaveFilter)

        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun hasRuntimePermissions(): Boolean {
        val hasPhoto = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return hasPhoto && hasContacts
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 102)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotif = isNotificationServiceEnabled()
        val hasPerms = hasRuntimePermissions()
        val hasSync = isAccessibilityServiceEnabled()
        val hasBattery = isIgnoringBatteryOptimizations()

        statusText.text =
            "1. Ekran Üstü Balon: ${if (hasOverlay) "AKTİF ✅" else "EKSİK ❌"}\n" +
            "2. Bildirim & Çağrı Yakalama: ${if (hasNotif) "AKTİF ✅" else "EKSİK ❌"}\n" +
            "3. Fotoğraf & Rehber (WA Arama): ${if (hasPerms) "AKTİF ✅" else "EKSİK ❌"}\n" +
            "4. Uygulama İçi Eşitleme: ${if (hasSync) "AKTİF ✅" else "İSTEĞE BAĞLI ⚡"}\n" +
            "5. Pil Kısıtlaması Muafiyeti: ${if (hasBattery) "AKTİF ✅" else "ÖNERİLİR 🔋"}\n\n" +
            "İpucu: Kısıtlanmış ayar uyarısı çıkarsa Ayarlar > Uygulamalar > SuperBubble > Sağ üst 3 nokta > Kısıtlanmış ayarlara izin ver demen yeterli."
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