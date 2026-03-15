package com.rakibulcodes.callerinfo

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import android.content.res.ColorStateList
import android.os.PowerManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import androidx.recyclerview.widget.LinearLayoutManager
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import com.rakibulcodes.callerinfo.data.TelegramManager
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: CallerInfoRepository
    private lateinit var telegramManager: TelegramManager
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var gestureDetector: GestureDetectorCompat
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isLoggingInFlow = false

    private val roleRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Call Screening role granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Call Screening role denied. Automatic Caller ID might not work on Android 10+.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)

        super.onCreate(savedInstanceState)

        gestureDetector = GestureDetectorCompat(this, SwipeGestureListener())

        repository = CallerInfoRepository.getInstance(applicationContext)
        telegramManager = TelegramManager.getInstance(applicationContext)
        initializeUI()
        observeTelegramState()

        setupNetworkListener()
        checkForUpdates()
    }

    private fun setupNetworkListener() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (telegramManager.isReady()) {
                    telegramManager.reconnect()
                }
            }
        }
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Official update source
                val updateUrl = "https://rakibulcodes.com/caller-info/version.json" 
                
                val request = Request.Builder()
                    .url(updateUrl)
                    .build()

                val client = OkHttpClient()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return@launch
                    val json = JSONObject(responseBody)
                    
                    val latestVersionCode = json.optInt("versionCode", 1)
                    val apkUrl = json.optString("apkUrl", "")
                    
                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode
                    }

                    if (latestVersionCode > currentVersionCode && apkUrl.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(apkUrl, json.optString("versionName", ""))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUpdateDialog(apkUrl: String, versionName: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Update Available")
            .setMessage("A new version ($versionName) is available. Please update to continue using Caller Info.")
            .setPositiveButton("Download") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun initializeUI() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupBottomNavigation()
        setupHistory()
        setupSetupSection()

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val savedNavId = prefs.getInt("active_nav", R.id.nav_lookup)
        if (savedNavId != R.id.nav_lookup) {
            binding.bottomNavigation.selectedItemId = savedNavId
        }

        binding.btnLookup.setOnClickListener {
            val number = binding.etLookupNumber.text.toString().trim()
            performLookup(number, showNotification = false)
        }

        binding.btnCopy.setOnClickListener {
            val name = binding.cardContent.tvName.text
            val number = binding.cardContent.tvNumber.text
            val carrier = binding.cardContent.tvCarrier.text
            val email = if (binding.cardContent.tvEmail.visibility == View.VISIBLE) "\nEmail: ${binding.cardContent.tvEmail.text}" else ""
            val location = if (binding.cardContent.tvLocation.visibility == View.VISIBLE) "\nLocation: ${binding.cardContent.tvLocation.text}" else ""
            val address = if (binding.cardContent.tvAddress.visibility == View.VISIBLE) "\nAddress: ${binding.cardContent.tvAddress.text}" else ""
            
            val textToCopy = "Name: $name, Number: $number, Carrier: $carrier$email$location$address"
            copyToClipboard(textToCopy)
        }

        binding.btnShare.setOnClickListener {
            val name = binding.cardContent.tvName.text
            val number = binding.cardContent.tvNumber.text
            val carrier = binding.cardContent.tvCarrier.text
            val email = if (binding.cardContent.tvEmail.visibility == View.VISIBLE) "\nEmail: ${binding.cardContent.tvEmail.text}" else ""
            val location = if (binding.cardContent.tvLocation.visibility == View.VISIBLE) "\nLocation: ${binding.cardContent.tvLocation.text}" else ""
            val address = if (binding.cardContent.tvAddress.visibility == View.VISIBLE) "\nAddress: ${binding.cardContent.tvAddress.text}" else ""

            val textToShare = "Name: $name, Number: $number, Carrier: $carrier$email$location$address"
            shareResult(textToShare)
        }

        // About section listeners
        binding.cardOfficialWebsite.setOnClickListener { openUrl("https://apps.rakibulcodes.com/caller-info") }
        binding.cardSourceCode.setOnClickListener { openUrl("https://github.com/rakibulcodes/caller-info") }
        binding.btnInfoWebsite.setOnClickListener { openUrl("https://rakibulcodes.com") }
        binding.btnInfoGithub.setOnClickListener { openUrl("https://github.com/rakibulcodes") }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.appVersionText.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            e.printStackTrace()
        }

        binding.permissionWarning.setOnClickListener {
            val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("enabled", false)
            if (!isEnabled && hasAllPermissions()) {
                binding.bottomNavigation.selectedItemId = R.id.nav_settings
            } else {
                promptForPermissions()
            }
        }

        binding.tvBatteryInfo.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        checkPermissions()
    }

    private fun observeTelegramState() {
        lifecycleScope.launch {
            telegramManager.authState.collectLatest { state ->
                updateLoginUi(state)
            }
        }
    }

    private fun updateLoginUi(state: TdApi.AuthorizationState) {
        binding.tilLoginCode.visibility = View.GONE
        binding.til2faPassword.visibility = View.GONE
        binding.btnLoginTelegram.isEnabled = true
        binding.btnLoginTelegram.text = "Login"

        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                binding.tvLoginStatus.text = "Enter API details & phone numbers to proceed."
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                binding.tvLoginStatus.text = "Enter API details & phone numbers to proceed."
                if (isLoggingInFlow) {
                    binding.tvLoginStatus.text = "Connecting..."
                    val phone = binding.etTelegramPhone.text.toString().trim()
                    if (phone.isNotEmpty()) {
                        telegramManager.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { }
                    }
                }
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                isLoggingInFlow = false
                binding.tvLoginStatus.text = "Enter code sent to Telegram"
                binding.tilLoginCode.visibility = View.VISIBLE
                binding.btnLoginTelegram.text = "Verify Code"
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                isLoggingInFlow = false
                binding.tvLoginStatus.text = "Enter your 2FA password"
                binding.til2faPassword.visibility = View.VISIBLE
                binding.btnLoginTelegram.text = "Submit Password"
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                binding.tvLoginStatus.text = "Logged in successfully!"
                binding.btnLoginTelegram.text = "Logout"
                updateStatusIndicator(getSharedPreferences("Settings", MODE_PRIVATE).getBoolean("enabled", false))
                
                // Join groups/bots once logged in
                telegramManager.performInitialSetup()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val historyItem = menu.findItem(R.id.action_clear_all)
        historyItem?.isVisible = binding.bottomNavigation.selectedItemId == R.id.nav_history
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_all -> {
                showClearHistoryDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
                .putInt("active_nav", item.itemId).apply()
            when (item.itemId) {
                R.id.nav_lookup -> {
                    binding.toolbar.title = "Caller Info"
                    showSection(binding.searchLayout)
                    invalidateOptionsMenu()
                    true
                }
                R.id.nav_history -> {
                    binding.toolbar.title = "Search History"
                    loadHistory()
                    showSection(binding.historyLayout)
                    invalidateOptionsMenu()
                    true
                }
                R.id.nav_settings -> {
                    binding.toolbar.title = "Setup"
                    showSection(binding.settingsLayout)
                    invalidateOptionsMenu()
                    true
                }
                R.id.nav_info -> {
                    binding.toolbar.title = "About"
                    showSection(binding.infoLayout)
                    invalidateOptionsMenu()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupHistory() {
        historyAdapter = HistoryAdapter(emptyList())
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter
        binding.rvHistory.isNestedScrollingEnabled = false
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val history = repository.getAllHistory()
            if (history.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.rvHistory.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.rvHistory.visibility = View.VISIBLE
                historyAdapter.updateData(history)
            }
        }
    }

    private fun showClearHistoryDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Clear History")
            .setMessage("Are you sure you want to clear all search history?")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    repository.clearHistory()
                    loadHistory()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSetupSection() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val tgPrefs = getSharedPreferences("TelegramSettings", Context.MODE_PRIVATE)
        
        binding.switchEnable.isChecked = prefs.getBoolean("enabled", false)
        binding.switchLookupKnown.isChecked = prefs.getBoolean("lookup_known", false)

        binding.etMaxHistory.setText(prefs.getString("max_history_size", "100"))
        binding.etMaxHistory.doAfterTextChanged { text ->
            prefs.edit().putString("max_history_size", text?.toString() ?: "100").apply()
        }

        binding.etAppId.setText(if (tgPrefs.getInt("api_id", 0) == 0) "" else tgPrefs.getInt("api_id", 0).toString())
        binding.etAppHash.setText(tgPrefs.getString("api_hash", ""))
        binding.etTelegramPhone.setText(tgPrefs.getString("phone", ""))

        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.toggleTheme.check(R.id.btnThemeLight)
            AppCompatDelegate.MODE_NIGHT_YES -> binding.toggleTheme.check(R.id.btnThemeDark)
            else -> binding.toggleTheme.check(R.id.btnThemeSystem)
        }

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                    R.id.btnThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                prefs.edit().putInt("theme_mode", mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }

        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!telegramManager.isReady()) {
                    binding.switchEnable.isChecked = false
                    binding.bottomNavigation.selectedItemId = R.id.nav_settings
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Setup Required")
                        .setMessage("Please complete the Telegram login first before enabling Caller ID.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnCheckedChangeListener
                }
                
                if (!hasAllPermissions()) {
                    binding.switchEnable.isChecked = false
                    promptForPermissions()
                    return@setOnCheckedChangeListener
                }

                // Check for Call Screening Role on Android 10+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = getSystemService(RoleManager::class.java)
                    if (roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true &&
                        !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                        roleRequestLauncher.launch(intent)
                    }
                }
            }
            prefs.edit().putBoolean("enabled", isChecked).apply()
            updateStatusIndicator(isChecked)
            
            // Re-setup network listener if it was skipped due to missing permission
            if (isChecked && networkCallback == null) {
                setupNetworkListener()
            }
        }

        binding.switchLookupKnown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("lookup_known", isChecked).apply()
        }

        binding.btnLoginTelegram.setOnClickListener {
            handleTelegramLogin()
        }
    }

    private fun handleTelegramLogin() {
        val tgPrefs = getSharedPreferences("TelegramSettings", Context.MODE_PRIVATE)
        val appIdStr = binding.etAppId.text.toString().trim()
        val appHash = binding.etAppHash.text.toString().trim()
        val phone = binding.etTelegramPhone.text.toString().trim()

        if (appIdStr.isEmpty() || appHash.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Please fill App ID, Hash and Phone", Toast.LENGTH_SHORT).show()
            return
        }

        tgPrefs.edit().apply {
            putInt("api_id", appIdStr.toIntOrNull() ?: 0)
            putString("api_hash", appHash)
            putString("phone", phone)
            apply()
        }

        val currentState = telegramManager.authState.replayCache.firstOrNull() ?: return
        
        isLoggingInFlow = true

        when (currentState.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                telegramManager.sendTdlibParameters(appIdStr.toIntOrNull() ?: 0, appHash)
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                telegramManager.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { }
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                val code = binding.etLoginCode.text.toString().trim()
                if (code.isEmpty()) return
                telegramManager.send(TdApi.CheckAuthenticationCode(code)) { }
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                val password = binding.et2faPassword.text.toString().trim()
                if (password.isEmpty()) return
                telegramManager.send(TdApi.CheckAuthenticationPassword(password)) { }
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                telegramManager.send(TdApi.LogOut()) { }
            }
        }
    }

    private fun showSection(targetView: View) {
        val views = listOf(binding.searchLayout, binding.historyLayout, binding.settingsLayout, binding.infoLayout)
        val currentIndex = views.indexOfFirst { it.visibility == View.VISIBLE }
        val targetIndex = views.indexOf(targetView)

        if (currentIndex == targetIndex) return

        // Cancel previous vertical scroll state and go to the top
        binding.mainScrollView.scrollTo(0, 0)
        binding.appBarLayout.setExpanded(true, false)

        val screenWidth = resources.displayMetrics.widthPixels.toFloat() / 2f 

        views.forEachIndexed { index, view ->
            if (view == targetView) {
                if (view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                    view.alpha = 0f

                    if (currentIndex != -1) {
                        view.translationX = if (targetIndex > currentIndex) screenWidth else -screenWidth
                    } else {
                        view.translationX = 0f
                        view.translationY = 30f
                    }

                    view.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction(null)
                        .start()
                }
            } else {
                if (view.visibility == View.VISIBLE) {
                    val distance = if (targetIndex > currentIndex) -screenWidth else screenWidth
                    view.animate()
                        .alpha(0f)
                        .translationX(distance)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction { 
                            view.visibility = View.GONE
                            view.translationX = 0f
                        }
                        .start()
                } else {
                    view.visibility = View.GONE
                }
            }
        }
    }


    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun hasAllPermissions(): Boolean {
        val permissions = mutableListOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return missingPermissions.isEmpty() && hasOverlayPermission()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        val overlayMissing = !hasOverlayPermission()
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)

        if (missingPermissions.isNotEmpty() || overlayMissing || !isEnabled) {
            binding.permissionWarning.visibility = View.VISIBLE

            val issues = mutableListOf<String>()
            if (!isEnabled) issues.add("Caller ID is disabled")

            if (missingPermissions.isNotEmpty()) {
                val names = missingPermissions.joinToString(", ") { perm ->
                    when (perm) {
                        android.Manifest.permission.READ_PHONE_STATE -> "Phone"
                        android.Manifest.permission.READ_CALL_LOG -> "Call Log"
                        android.Manifest.permission.READ_CONTACTS -> "Contacts"
                        android.Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                        else -> perm.substringAfterLast(".")
                    }
                }
                issues.add("Missing permissions: $names")
            }
            if (overlayMissing) issues.add("Overlay required")
            
            val text = "${issues.joinToString(". ")}. Tap here to fix."
            val spannableString = android.text.SpannableString(text)
            val tapIndex = text.indexOf("Tap here")
            if (tapIndex != -1) {
                spannableString.setSpan(android.text.style.UnderlineSpan(), tapIndex, text.length, 0)
            }
            binding.permissionWarning.text = spannableString
        } else {
            binding.permissionWarning.visibility = View.GONE
        }
        
        // Ensure network listener is active if permission is granted
        if (networkCallback == null && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED) {
            setupNetworkListener()
        }
    }

    private fun promptForPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            val names = missingPermissions.joinToString(", ") { perm ->
                when (perm) {
                    android.Manifest.permission.READ_PHONE_STATE -> "Phone"
                    android.Manifest.permission.READ_CALL_LOG -> "Call Log"
                    android.Manifest.permission.READ_CONTACTS -> "Contacts"
                    android.Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                    else -> perm.substringAfterLast(".")
                }
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Permissions Required")
                .setMessage("Please allow the following permissions: $names")
                .setPositiveButton("Allow") { _, _ -> 
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissions(missingPermissions.toTypedArray(), 101)
                    }
                }
                .setNeutralButton("App Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (!hasOverlayPermission()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Overlay Permission")
                .setMessage("To automatically show Caller ID over other apps during an incoming call, please allow the 'Display over other apps' permission.")
                .setPositiveButton("Open Settings") { _, _ -> requestOverlayPermission() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Battery Optimization")
                .setMessage("To ensure Caller ID works reliably in the background, please go to App Info -> Battery, and select 'Unrestricted'.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Caller Info", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareResult(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun performLookup(number: String, showNotification: Boolean) {
        if (!telegramManager.isReady()) {
            Toast.makeText(this, "Please complete Telegram setup first", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (number.isEmpty()) {
            Toast.makeText(this, "Enter a number first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnLookup.isEnabled = false
        binding.resultLayout.visibility = View.VISIBLE
        with(binding.cardContent) {
            tvName.text = "Searching..."
            tvNumber.text = number
            tvCarrier.text = ""
            tvEmail.visibility = View.GONE
            tvLocation.visibility = View.GONE
            tvAddress.visibility = View.GONE
            tvError.visibility = View.GONE
        }

        lifecycleScope.launch {
            val result = repository.getCallerInfo(number)
            
            binding.btnLookup.isEnabled = true
            updateResultUI(result)
            
            if (showNotification) {
                val message = buildNotificationMessage(result)
                NotificationHelper.showNotification(this@MainActivity, "Result", message)
            }
        }
    }

    private fun updateResultUI(info: CallerInfoEntity) {
        with(binding.cardContent) {
            tvName.text = info.name ?: "Unknown"
            tvNumber.text = info.number
            
            val carrierText = listOfNotNull(info.carrier, info.country).joinToString(", ")
            tvCarrier.text = if (carrierText.isNotEmpty()) carrierText else "Unknown Carrier"
            
            if (!info.email.isNullOrEmpty()) {
                tvEmail.text = info.email
                tvEmail.visibility = View.VISIBLE
            } else {
                tvEmail.visibility = View.GONE
            }

            if (!info.location.isNullOrEmpty()) {
                tvLocation.text = info.location
                tvLocation.visibility = View.VISIBLE
            } else {
                tvLocation.visibility = View.GONE
            }

            val fullAddress = listOfNotNull(info.address1, info.address2).joinToString("\n")
            if (fullAddress.isNotEmpty()) {
                tvAddress.text = fullAddress
                tvAddress.visibility = View.VISIBLE
            } else {
                tvAddress.visibility = View.GONE
            }
            
            if (info.error != null) {
                 tvError.text = info.error
                 tvError.visibility = View.VISIBLE
            } else {
                 tvError.visibility = View.GONE
            }
        }
    }

    private fun buildNotificationMessage(info: CallerInfoEntity): String {
        val sb = StringBuilder()
        sb.append(info.name ?: "Unknown")
        
        val carrierInfo = listOfNotNull(info.carrier, info.country).joinToString(", ")
        if (carrierInfo.isNotEmpty()) {
            sb.append("\n").append(carrierInfo)
        }
        
        if (!info.email.isNullOrEmpty()) {
            sb.append("\nEmail: ").append(info.email)
        }
        if (!info.location.isNullOrEmpty()) {
            sb.append("\nLocation: ").append(info.location)
        }
        if (!info.address1.isNullOrEmpty()) {
            sb.append("\nAddress1: ").append(info.address1)
        }
        if (!info.address2.isNullOrEmpty()) {
            sb.append("\nAddress2: ").append(info.address2)
        }
        return sb.toString()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            checkPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            checkPermissions()
            val prefs = getSharedPreferences("Settings", MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("enabled", false)
            
            updateStatusIndicator(isEnabled)
        }
    }

    private fun updateStatusIndicator(enabled: Boolean) {
        val color = ContextCompat.getColor(this, if (enabled && telegramManager.isReady()) R.color.status_active else R.color.status_inactive)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private inner class SwipeGestureListener : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val diffY = e2.y - e1.y
            val diffX = e2.x - e1.x
            if (abs(diffX) > abs(diffY) && abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffX > 0) {
                    navigatePrev()
                } else {
                    navigateNext()
                }
                return true
            }
            return false
        }
    }

    private fun navigateNext() {
        val menu = binding.bottomNavigation.menu
        for (i in 0 until menu.size() - 1) {
            if (menu.getItem(i).isChecked) {
                binding.bottomNavigation.selectedItemId = menu.getItem(i + 1).itemId
                break
            }
        }
    }

    private fun navigatePrev() {
        val menu = binding.bottomNavigation.menu
        for (i in 1 until menu.size()) {
            if (menu.getItem(i).isChecked) {
                binding.bottomNavigation.selectedItemId = menu.getItem(i - 1).itemId
                break
            }
        }
    }
}
