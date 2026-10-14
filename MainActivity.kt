package com.selim.superbubble

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

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
            text = "SuperBubble Kontrol Paneli"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 40)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 60)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Ekran Üstü Balon İznini Ver"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Bu izin zaten tamam kanka!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnNotification = Button(this).apply {
            text = "2. Bildirim Yakalama İznini Ver"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
        layout.addView(btnTest)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotif = isNotificationServiceEnabled()
        statusText.text = "Ekran Üstü İzni: ${if (hasOverlay) "AKTİF ✅" else "EKSİK ❌"}\n" +
                "Bildirim İzni: ${if (hasNotif) "AKTİF ✅" else "EKSİK ❌"}\n\n" +
                "Her iki izin aktif olduğunda WhatsApp ve Instagram mesajların otomatik olarak balona dönüşür."
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, BubbleService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }
}
