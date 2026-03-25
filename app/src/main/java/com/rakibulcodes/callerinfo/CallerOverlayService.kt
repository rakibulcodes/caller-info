package com.rakibulcodes.callerinfo

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.view.*
import android.content.res.ColorStateList
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import kotlin.math.sqrt

class CallerOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra("number") ?: return START_NOT_STICKY
        val name = intent.getStringExtra("name")
        val carrier = intent.getStringExtra("carrier")
        val country = intent.getStringExtra("country")
        val location = intent.getStringExtra("location")
        val email = intent.getStringExtra("email")
        val error = intent.getStringExtra("error")

        showOverlay(number, name, carrier, country, location, email, error)
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(number: String, name: String?, carrier: String?, country: String?, location: String?, email: String? = null, error: String? = null) {
        removeOverlayInternal() // Remove existing overlay if any

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val isNightMode = when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        val nightConfig = Configuration(resources.configuration)
        nightConfig.uiMode = (nightConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isNightMode) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val configContext = applicationContext.createConfigurationContext(nightConfig)
        val themeContext = ContextThemeWrapper(configContext, R.style.Theme_CallerInfo)
        val inflater = LayoutInflater.from(themeContext)
        
        try {
            overlayView = inflater.inflate(R.layout.layout_overlay_card, null)

            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            // Calculate width with a max limit for landscape/tablets
            val maxWidthPx = (420 * displayMetrics.density).toInt()
            val preferredWidth = (screenWidth * 0.95).toInt()
            val finalWidth = if (preferredWidth > maxWidthPx) maxWidthPx else preferredWidth

            params = WindowManager.LayoutParams(
                finalWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            
            params?.gravity = Gravity.CENTER
            // Use percentage of screen height for Y offset
            params?.y = if (isLandscape) (screenHeight * 0.15f).toInt() else -(screenHeight * 0.15f).toInt()

            overlayView?.let { view ->
                // Set status indicator color
                val statusIndicator = view.findViewById<View>(R.id.statusIndicator)
                if (error != null) {
                    statusIndicator.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336")) // Red
                } else if (name != null && name != "Unknown") {
                    statusIndicator.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50")) // Green
                } else {
                    statusIndicator.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#FFC107")) // Yellow
                }

                view.findViewById<TextView>(R.id.tvName).text = name ?: "Unknown"

                val carrierText = listOfNotNull(carrier, country).joinToString(" · ")
                view.findViewById<TextView>(R.id.tvCarrier).text = if (carrierText.isNotEmpty()) carrierText else "Unknown Carrier"
                
                val rowEmail = view.findViewById<LinearLayout>(R.id.rowEmail)
                val tvEmail = view.findViewById<TextView>(R.id.tvEmail)
                if (!email.isNullOrEmpty()) {
                    tvEmail.text = email
                    rowEmail.visibility = View.VISIBLE
                } else {
                    rowEmail.visibility = View.GONE
                }

                val rowLocation = view.findViewById<LinearLayout>(R.id.rowLocation)
                val tvLocation = view.findViewById<TextView>(R.id.tvLocation)
                if (!location.isNullOrEmpty()) {
                    tvLocation.text = location
                    rowLocation.visibility = View.VISIBLE
                } else {
                    rowLocation.visibility = View.GONE
                }

                val resolvedName = name?.takeIf { it.isNotBlank() } ?: "Unknown"
                val shareText = buildOverlayShareText(
                    number = number,
                    name = resolvedName,
                    carrier = carrier,
                    country = country,
                    email = email,
                    location = location
                )

                view.findViewById<View>(R.id.btnOverlaySave).setOnClickListener {
                    val insertIntent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, resolvedName)
                        putExtra(ContactsContract.Intents.Insert.PHONE, number)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(insertIntent)
                }

                view.findViewById<View>(R.id.btnOverlayCopy).setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("caller_info_overlay", shareText))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }

                view.findViewById<View>(R.id.btnOverlayShare).setOnClickListener {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share via").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }

                // Handle close button click
                view.findViewById<View>(R.id.btnClose).setOnClickListener {
                    animateDismiss(view, 0f, 300f)
                }

                // Swipe-to-dismiss in any direction
                var initialX = 0f
                var initialY = 0f
                var initialTouchX = 0f
                var initialTouchY = 0f

                view.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params?.x?.toFloat() ?: 0f
                            initialY = params?.y?.toFloat() ?: 0f
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            view.animate().cancel()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = event.rawX - initialTouchX
                            val deltaY = event.rawY - initialTouchY
                            
                            params?.x = (initialX + deltaX).toInt()
                            params?.y = (initialY + deltaY).toInt()
                            
                            val distance = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
                            val alpha = (1f - distance / 600f).coerceIn(0.2f, 1f)
                            val scale = (1f - distance / 2400f).coerceIn(0.85f, 1f)
                            view.alpha = alpha
                            view.scaleX = scale
                            view.scaleY = scale
                            
                            try {
                                windowManager?.updateViewLayout(view, params)
                            } catch (e: Exception) {}
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val deltaX = event.rawX - initialTouchX
                            val deltaY = event.rawY - initialTouchY
                            val distance = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
                            val velocity = distance / 0.3f 
                            
                            if (distance > 200 || velocity > 1200) {
                                val angle = Math.atan2(deltaY.toDouble(), deltaX.toDouble())
                                val flingDist = 800f
                                val targetX = (initialX + Math.cos(angle) * flingDist).toFloat()
                                val targetY = (initialY + Math.sin(angle) * flingDist).toFloat()
                                animateDismiss(view, targetX - (params?.x ?: 0), targetY - (params?.y ?: 0))
                            } else {
                                params?.x = initialX.toInt()
                                params?.y = initialY.toInt()
                                try {
                                    windowManager?.updateViewLayout(view, params)
                                } catch (e: Exception) {}
                                view.animate()
                                    .alpha(1f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(300)
                                    .setInterpolator(OvershootInterpolator(2f))
                                    .start()
                            }
                            true
                        }
                        else -> false
                    }
                }

                windowManager?.addView(view, params)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun animateDismiss(view: View, translationX: Float, translationY: Float) {
        view.animate()
            .translationXBy(translationX)
            .translationYBy(translationY)
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(280)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction { 
                view.visibility = View.GONE
                removeOverlay() 
            }
            .start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayView?.let { view ->
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            val maxWidthPx = (420 * displayMetrics.density).toInt()
            val preferredWidth = (screenWidth * 0.95).toInt()
            
            params?.width = if (preferredWidth > maxWidthPx) maxWidthPx else preferredWidth
            // Update Y offset using percentage on configuration change
            params?.y = if (isLandscape) (screenHeight * 0.15f).toInt() else -(screenHeight * 0.15f).toInt()

            try {
                windowManager?.updateViewLayout(view, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun removeOverlayInternal() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
            overlayView = null
        }
    }

    private fun removeOverlay() {
        removeOverlayInternal()
        stopSelf()
    }

    private fun buildOverlayShareText(
        number: String,
        name: String,
        carrier: String?,
        country: String?,
        email: String?,
        location: String?
    ): String {
        val sb = StringBuilder()
        sb.append("Name: ").append(name)
        sb.append("\nNumber: ").append(number)

        val carrierText = listOfNotNull(carrier, country).joinToString(", ")
        if (carrierText.isNotEmpty()) sb.append("\n").append(carrierText)
        if (!email.isNullOrEmpty()) sb.append("\nEmail: ").append(email)
        if (!location.isNullOrEmpty()) sb.append("\nLocation: ").append(location)

        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlayInternal()
    }
}
