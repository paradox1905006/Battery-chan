package com.tg.batterychan.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tg.batterychan.R
import com.tg.batterychan.util.Backup
import androidx.core.app.NotificationManagerCompat
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tg.batterychan.data.BatteryEvent
import com.tg.batterychan.data.CustomEvent
import com.tg.batterychan.data.FileManager
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.data.Trigger
import com.tg.batterychan.databinding.ActivitySettingsBinding
import com.tg.batterychan.databinding.DialogCustomEventBinding
import com.tg.batterychan.databinding.ItemEventSettingsBinding
import com.google.android.material.slider.Slider
import com.tg.batterychan.service.BatteryService
import com.tg.batterychan.service.MiniPlayerController
import com.tg.batterychan.util.ImageLoader
import com.tg.batterychan.util.NotificationHelper
import java.io.File

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    // Which event/type the file picker was opened for (kept across recreation).
    private var pendingKey: String? = null
    private var pendingType: Prefs.FileType? = null

    private val eventViews = mutableMapOf<String, ItemEventSettingsBinding>()
    private var suppressScaleSave = false

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { runBackup(it, export = true) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { runBackup(it, export = false) }
        }

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handleFilePicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        prefs = Prefs(this)

        pendingKey = savedInstanceState?.getString(STATE_PENDING_KEY)
        pendingType = savedInstanceState?.getString(STATE_PENDING_TYPE)
            ?.let { tag -> Prefs.FileType.values().firstOrNull { it.tag == tag } }

        setupToolbar()
        setupAppearance()
        setupGlobalSettings()
        buildPermissionRows()
        setupMiniPlayer()
        setupSpeedMeter()
        setupBackup()
        buildEventList()
        setupPowerIndicatorSettings()

        binding.btnAddEvent.setOnClickListener { showEventDialog(null) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_KEY, pendingKey)
        outState.putString(STATE_PENDING_TYPE, pendingType?.tag)
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted a permission or used the notification's Lock button.
        if (::prefs.isInitialized) {
            updateMiniUsageUi()
            updateMiniDrawerUi()
            if (binding.switchSpeedNotif.isChecked != prefs.isSpeedInNotification()) {
                binding.toggleSpeedUnit.check(
            if (prefs.isSpeedBits()) binding.btnUnitBits.id else binding.btnUnitBytes.id
        )
        binding.toggleSpeedUnit.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.setSpeedBits(checkedId == binding.btnUnitBits.id)
            MiniPlayerController.sync(this)
        }

        buildSpeedColors()
        binding.etSpeedHex.doAfterTextChanged { text ->
            if (updatingHex) return@doAfterTextChanged
            val hex = text?.toString().orEmpty().removePrefix("#").trim()
            if (hex.length != 6) return@doAfterTextChanged
            val rgb = hex.toIntOrNull(16) ?: return@doAfterTextChanged
            setSpeedColor(0xFF000000.toInt() or rgb, fromHex = true)
        }

        binding.sliderSpeedSize.value = prefs.getSpeedSize().toFloat()
        binding.sliderSpeedSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setSpeedSize(value.toInt())
        }
        binding.sliderSpeedSize.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = MiniPlayerController.sync(this@SettingsActivity)
        })

        binding.btnSpeedResetPos.setOnClickListener {
            prefs.setSpeedPos(0.5f, 0.92f)
            MiniPlayerController.sync(this)
        }

        binding.switchSpeedNotif.isChecked = prefs.isSpeedInNotification()
            }
            if (binding.switchMiniEnabled.isChecked != prefs.isMiniEnabled()) {
                binding.switchMiniEnabled.isChecked = prefs.isMiniEnabled()
            }
            if (binding.switchMiniLock.isChecked != prefs.isMiniLocked()) {
                binding.switchMiniLock.isChecked = prefs.isMiniLocked()
            }
            if (binding.switchMiniLockLockscreen.isChecked != prefs.isMiniLocked(true)) {
                binding.switchMiniLockLockscreen.isChecked = prefs.isMiniLocked(true)
            }
            buildPermissionRows()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    // ---------------------------------------------------------------- Appearance

    private fun setupAppearance() {
        val checkedButton = when (prefs.getThemeMode()) {
            Prefs.THEME_LIGHT -> binding.btnThemeLight
            Prefs.THEME_DARK -> binding.btnThemeDark
            Prefs.THEME_BLACK -> binding.btnThemeBlack
            else -> binding.btnThemeSystem
        }
        binding.toggleTheme.check(checkedButton.id)
        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                binding.btnThemeLight.id -> Prefs.THEME_LIGHT
                binding.btnThemeDark.id -> Prefs.THEME_DARK
                binding.btnThemeBlack.id -> Prefs.THEME_BLACK
                else -> Prefs.THEME_SYSTEM
            }
            if (mode != prefs.getThemeMode()) changeAppearance { prefs.setThemeMode(mode) }
        }

        // Dynamic color (Material You wallpaper colors) only exists on Android 12+.
        if (DynamicColors.isDynamicColorAvailable()) {
            binding.switchDynamicColor.isChecked = prefs.isDynamicColor()
            binding.switchDynamicColor.setOnCheckedChangeListener { _, isChecked ->
                changeAppearance { prefs.setDynamicColor(isChecked) }
            }
        } else {
            binding.switchDynamicColor.visibility = View.GONE
        }
    }

    private fun changeAppearance(update: () -> Unit) {
        val oldNight = ThemeUtils.nightModeFor(prefs.getThemeMode())
        update()
        val newNight = ThemeUtils.nightModeFor(prefs.getThemeMode())
        ThemeUtils.apply(prefs)
        // AppCompat recreates activities itself when the night mode changes. Dark <-> Black
        // and dynamic color share a night mode, so those need a manual recreate.
        if (oldNight == newNight) recreate()
    }

    // ---------------------------------------------------------------- Events

    private fun buildEventList() {
        binding.containerEvents.removeAllViews()
        eventViews.clear()
        BatteryEvent.values().forEach { addEventCard(it.key, it.displayName, it.description, null) }
        prefs.getCustomEvents().forEach { addEventCard(it.key, it.name, it.description, it) }
    }

    private fun addEventCard(key: String, title: String, subtitle: String, custom: CustomEvent?) {
        val item = ItemEventSettingsBinding.inflate(layoutInflater, binding.containerEvents, false)
        binding.containerEvents.addView(item.root)
        eventViews[key] = item

        item.tvEventTitle.text = title
        item.tvEventSubtitle.text = subtitle

        item.swEnabled.isChecked = prefs.isEventEnabled(key)
        item.swEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.setEventEnabled(key, isChecked)
        }

        item.etEventText.setText(prefs.getEventText(key))
        item.etEventText.doAfterTextChanged { prefs.setEventText(key, it?.toString()) }

        item.btnPickAudio.setOnClickListener { pickFile(key, Prefs.FileType.AUDIO, arrayOf("audio/*")) }
        item.btnPickImage.setOnClickListener { pickFile(key, Prefs.FileType.IMAGE, arrayOf("image/*", "video/*")) }
        item.btnRemoveAudio.setOnClickListener { removeFile(key, Prefs.FileType.AUDIO) }
        item.btnRemoveImage.setOnClickListener { removeFile(key, Prefs.FileType.IMAGE) }
        item.btnTest.setOnClickListener { testEvent(key, title) }

        item.toggleScale.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressScaleSave) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                item.btnScaleStretch.id -> Prefs.SCALE_STRETCH
                item.btnScaleFit.id -> Prefs.SCALE_FIT
                else -> Prefs.SCALE_FILL
            }
            prefs.setEventScale(key, mode)
            applyPreviewFit(item, mode)
        }

        if (custom != null) {
            item.btnEdit.visibility = View.VISIBLE
            item.btnDelete.visibility = View.VISIBLE
            item.btnEdit.setOnClickListener { showEventDialog(custom) }
            item.btnDelete.setOnClickListener { confirmDelete(custom) }
        } else {
            item.btnEdit.visibility = View.GONE
            item.btnDelete.visibility = View.GONE
        }

        updateMediaUI(item, key)
    }

    private fun pickFile(key: String, type: Prefs.FileType, mime: Array<String>) {
        pendingKey = key
        pendingType = type
        pickFileLauncher.launch(mime)
    }

    /**
     * Test = notification preview + the real pop-up. The pop-up already plays the event
     * sound, so nothing is played here. (Playing it here too is what caused the echo.)
     */
    private fun testEvent(key: String, title: String) {
        val audioPath = prefs.getFilePath(key, Prefs.FileType.AUDIO)
        val imagePath = prefs.getFilePath(key, Prefs.FileType.IMAGE)
        val customText = prefs.getEventText(key)

        NotificationHelper.showEvent(this, key, "Preview: $title", customText, imagePath)

        startActivity(
            Intent(this, PopUpActivity::class.java).apply {
                putExtra("event_title", title)
                putExtra("image_path", imagePath)
                putExtra("audio_path", audioPath)
                putExtra("custom_text", customText)
                putExtra("scale_mode", prefs.getEventScale(key, imagePath))
                putExtra("duration", prefs.getNotificationDuration())
            }
        )
    }

    private fun removeFile(key: String, type: Prefs.FileType) {
        prefs.getFilePath(key, type)?.let { File(it).delete() }
        prefs.setFilePath(key, type, null)
        eventViews[key]?.let { updateMediaUI(it, key) }
    }

    private fun updateMediaUI(item: ItemEventSettingsBinding, key: String) {
        val audioPath = prefs.getFilePath(key, Prefs.FileType.AUDIO)
        val imagePath = prefs.getFilePath(key, Prefs.FileType.IMAGE)

        item.tvAudioName.text = audioPath?.let { describeFile(it) } ?: "No audio selected"
        item.btnRemoveAudio.visibility = if (audioPath != null) View.VISIBLE else View.GONE

        item.tvImageName.text = imagePath?.let { describeFile(it) } ?: "No image / GIF / video selected"
        item.btnRemoveImage.visibility = if (imagePath != null) View.VISIBLE else View.GONE

        if (imagePath != null && File(imagePath).exists()) {
            item.ivPreview.visibility = View.VISIBLE
            ImageLoader.load(item.ivPreview, imagePath, 256)
        } else {
            item.ivPreview.visibility = View.GONE
            ImageLoader.clear(item.ivPreview)
        }

        // Picture fit only matters when there is a picture. Showing the choice must not save it.
        item.llScale.visibility = if (imagePath != null && File(imagePath).exists()) View.VISIBLE else View.GONE
        suppressScaleSave = true
        item.toggleScale.check(
            when (prefs.getEventScale(key, imagePath)) {
                Prefs.SCALE_STRETCH -> item.btnScaleStretch.id
                Prefs.SCALE_FIT -> item.btnScaleFit.id
                else -> item.btnScaleFill.id
            }
        )
        suppressScaleSave = false
        applyPreviewFit(item, prefs.getEventScale(key, imagePath))
    }

    /** The preview is phone-shaped, so it shows how Fill / Stretch / Fit will look on the pop-up. */
    private fun applyPreviewFit(item: ItemEventSettingsBinding, mode: Int) {
        item.ivPreview.scaleType = when (mode) {
            Prefs.SCALE_STRETCH -> ImageView.ScaleType.FIT_XY
            Prefs.SCALE_FIT -> ImageView.ScaleType.FIT_CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }
        item.ivPreview.setBackgroundColor(if (mode == Prefs.SCALE_FIT) Color.BLACK else Color.TRANSPARENT)
    }

    private fun describeFile(path: String): String {
        val info = FileManager.getFileInfo(path) ?: return File(path).name
        return "${info.name} (${info.size / 1024} KB)"
    }

    private fun handleFilePicked(uri: Uri) {
        val key = pendingKey ?: return
        val type = pendingType ?: return

        when (val result = FileManager.saveFile(this, uri, key, type)) {
            is FileManager.SaveResult.Success -> {
                if (key.startsWith(Prefs.MINI_KEY)) {
                    // Mini player playlist item. The first one decides the size of the player.
                    prefs.addMiniItem(result.path)
                    if (prefs.getMiniPlaylist().size == 1) prefs.resetMiniSize()
                    updateMiniMediaUi()
                    MiniPlayerController.sync(this)
                } else {
                    prefs.setFilePath(key, type, result.path)
                    eventViews[key]?.let { updateMediaUI(it, key) }
                }
            }
            is FileManager.SaveResult.Error ->
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- Mini player

    private fun setupMiniPlayer() {
        binding.switchMiniEnabled.isChecked = prefs.isMiniEnabled()
        binding.switchMiniEnabled.setOnCheckedChangeListener { button, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    button.isChecked = false
                    Toast.makeText(
                        this,
                        "Please allow 'Display over other apps', then turn this on again.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                    return@setOnCheckedChangeListener
                }
                if (prefs.getMiniMediaPath() == null) {
                    Toast.makeText(
                        this,
                        "Pick an image / GIF / video below to see the mini player.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            prefs.setMiniEnabled(isChecked)
            MiniPlayerController.sync(this)
        }

        binding.btnMiniPick.setOnClickListener {
            // Every item gets its own key, so adding never replaces an earlier one.
            pickFile(
                "${Prefs.MINI_KEY}_${System.currentTimeMillis()}",
                Prefs.FileType.IMAGE,
                arrayOf("image/*", "video/*")
            )
        }
        binding.toggleMiniOrder.check(
            if (prefs.isMiniShuffle()) binding.btnMiniOrderShuffle.id else binding.btnMiniOrderSeq.id
        )
        binding.toggleMiniOrder.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.setMiniShuffle(checkedId == binding.btnMiniOrderShuffle.id)
            MiniPlayerController.sync(this)
        }
        binding.btnMiniSwitch.setOnClickListener { showSwitchDialog() }
        updateMiniMediaUi()

        binding.switchMiniLock.isChecked = prefs.isMiniLocked()
        binding.switchMiniLock.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMiniLocked(isChecked)
            MiniPlayerController.sync(this)
        }

        binding.switchMiniLockLockscreen.isChecked = prefs.isMiniLocked(true)
        binding.switchMiniLockLockscreen.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMiniLocked(isChecked, true)
            MiniPlayerController.sync(this)
        }

        binding.switchMiniSnap.isChecked = prefs.isMiniSnap()
        binding.switchMiniSnap.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMiniSnap(isChecked)
            MiniPlayerController.sync(this)
        }

        binding.sliderMiniPauseBelow.value = prefs.getMiniPauseBelow().toFloat()
        updatePauseBelowText(prefs.getMiniPauseBelow())
        binding.sliderMiniPauseBelow.setLabelFormatter { if (it <= 0f) "Off" else "${it.toInt()}%" }
        binding.sliderMiniPauseBelow.addOnChangeListener { _, value, fromUser ->
            updatePauseBelowText(value.toInt())
            if (fromUser) prefs.setMiniPauseBelow(value.toInt())
        }
        binding.sliderMiniPauseBelow.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = MiniPlayerController.sync(this@SettingsActivity)
        })

        binding.switchMiniHomeOnly.isChecked = prefs.isMiniHomeOnly()
        binding.switchMiniHomeOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMiniHomeOnly(isChecked)
            updateMiniUsageUi()
            MiniPlayerController.sync(this)
        }
        binding.btnMiniUsage.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        updateMiniUsageUi()

        binding.switchMiniHideDrawer.isChecked = prefs.isMiniHideDrawer()
        binding.switchMiniHideDrawer.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMiniHideDrawer(isChecked)
            updateMiniDrawerUi()
            MiniPlayerController.sync(this)
        }
        binding.btnMiniAccessibility.setOnClickListener {
            Toast.makeText(
                this,
                "Turn on 'Battery Chan mini player'. If it is greyed out, open App info > menu > Allow restricted settings first.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.etMiniDrawerMarker.setText(prefs.getMiniDrawerMarker())
        binding.etMiniDrawerMarker.doAfterTextChanged { prefs.setMiniDrawerMarker(it?.toString().orEmpty()) }
        binding.switchMiniShowLock.isChecked = prefs.isMiniShowOnLock()
        binding.switchMiniShowLock.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMiniShowOnLock(isChecked)
            updateMiniDrawerUi()
            MiniPlayerController.sync(this)
        }
        updateMiniDrawerUi()

        val scaleButton = when (prefs.getMiniScaleMode()) {
            Prefs.MINI_SCALE_STRETCH -> binding.btnMiniScaleStretch
            Prefs.MINI_SCALE_FIT -> binding.btnMiniScaleFit
            else -> binding.btnMiniScaleFill
        }
        binding.toggleMiniScale.check(scaleButton.id)
        binding.toggleMiniScale.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.setMiniScaleMode(
                when (checkedId) {
                    binding.btnMiniScaleStretch.id -> Prefs.MINI_SCALE_STRETCH
                    binding.btnMiniScaleFit.id -> Prefs.MINI_SCALE_FIT
                    else -> Prefs.MINI_SCALE_CROP
                }
            )
            MiniPlayerController.sync(this)
        }

        binding.sliderMiniOpacity.value = prefs.getMiniOpacity().toFloat()
        binding.sliderMiniOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setMiniOpacity(value.toInt())
        }
        binding.sliderMiniOpacity.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = MiniPlayerController.sync(this@SettingsActivity)
        })

        binding.sliderMiniCorner.value = prefs.getMiniCorner().toFloat()
        binding.sliderMiniCorner.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setMiniCorner(value.toInt())
        }
        binding.sliderMiniCorner.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = MiniPlayerController.sync(this@SettingsActivity)
        })

        binding.switchMiniPauseSaver.isChecked = prefs.isMiniPauseInSaver()
        binding.switchMiniPauseSaver.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMiniPauseInSaver(isChecked)
            MiniPlayerController.sync(this)
        }

        binding.btnMiniReset.setOnClickListener {
            prefs.resetMiniGeometry()
            MiniPlayerController.sync(this)
            Toast.makeText(this, "Position and size reset.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSpeedMeter() {
        binding.switchSpeedUnder.isChecked = prefs.isSpeedUnderPlayer()
        binding.switchSpeedUnder.setOnCheckedChangeListener { _, isChecked ->
            prefs.setSpeedUnderPlayer(isChecked)
            if (isChecked && !prefs.isMiniEnabled()) {
                Toast.makeText(this, "Turn on the mini player to see the speed inside it.", Toast.LENGTH_LONG).show()
            }
            MiniPlayerController.sync(this)
        }
        binding.switchSpeedNotif.isChecked = prefs.isSpeedInNotification()
        binding.switchSpeedNotif.setOnCheckedChangeListener { _, isChecked ->
            prefs.setSpeedInNotification(isChecked)
            if (isChecked && android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102
                )
            }
            MiniPlayerController.sync(this)
        }
        binding.switchSpeedAutoColor.isChecked = prefs.isSpeedColorAuto()
        binding.switchSpeedAutoColor.setOnCheckedChangeListener { _, isChecked ->
            prefs.setSpeedColorAuto(isChecked)
            MiniPlayerController.sync(this)
        }
    }

    private val speedPalette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFEB3B.toInt(), 0xFFFF9800.toInt(),
        0xFFF44336.toInt(), 0xFFFF4081.toInt(), 0xFFB388FF.toInt(), 0xFF448AFF.toInt(),
        0xFF00E5FF.toInt(), 0xFF69F0AE.toInt()
    )
    private var updatingHex = false

    private fun buildSpeedColors() {
        val row = binding.llSpeedColors
        row.removeAllViews()
        val size = (36 * resources.displayMetrics.density).toInt()
        val margin = (4 * resources.displayMetrics.density).toInt()
        speedPalette.forEach { color ->
            val swatch = View(this)
            swatch.layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            }
            swatch.contentDescription = "Speed colour"
            swatch.tag = color
            swatch.setOnClickListener { setSpeedColor(color, fromHex = false) }
            row.addView(swatch)
        }
        refreshSpeedSwatches()
        updatingHex = true
        binding.etSpeedHex.setText(String.format("%06X", prefs.getSpeedColor() and 0xFFFFFF))
        updatingHex = false
    }

    private fun refreshSpeedSwatches() {
        val density = resources.displayMetrics.density
        val current = prefs.getSpeedColor()
        for (i in 0 until binding.llSpeedColors.childCount) {
            val swatch = binding.llSpeedColors.getChildAt(i)
            val color = swatch.tag as Int
            val selected = color == current
            swatch.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    ((if (selected) 3 else 1) * density).toInt(),
                    if (selected) 0xFF888888.toInt() else 0x66808080
                )
            }
        }
    }

    private fun setSpeedColor(color: Int, fromHex: Boolean) {
        prefs.setSpeedColor(color)
        refreshSpeedSwatches()
        if (!fromHex) {
            updatingHex = true
            binding.etSpeedHex.setText(String.format("%06X", color and 0xFFFFFF))
            updatingHex = false
        }
        MiniPlayerController.sync(this)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    /** Rebuilds the list of mini player items (thumbnail, name, remove button). */
    private fun updateMiniMediaUi() {
        val box = binding.llMiniItems
        box.removeAllViews()
        val items = prefs.getMiniPlaylist()
        binding.tvMiniEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.llMiniPlaylistOpts.visibility = if (items.size >= 2) View.VISIBLE else View.GONE
        updateSwitchButtonText()

        val tint = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        items.forEach { path ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            row.addView(thumb, LinearLayout.LayoutParams(dp(56), dp(56)))
            ImageLoader.load(thumb, path, 128)

            val name = TextView(this).apply {
                text = describeFile(path)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setPadding(dp(12), 0, dp(8), 0)
            }
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val remove = ImageButton(this).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = tint
                contentDescription = "Remove from mini player"
                background = null
                setOnClickListener {
                    ImageLoader.clear(thumb)
                    File(path).delete()
                    prefs.removeMiniItem(path)
                    if (prefs.getMiniPlaylist().isEmpty()) prefs.resetMiniSize()
                    updateMiniMediaUi()
                    MiniPlayerController.sync(this@SettingsActivity)
                }
            }
            row.addView(remove, LinearLayout.LayoutParams(dp(48), dp(48)))
            box.addView(row)
        }
    }

    private val switchChoices = listOf(
        0 to "Never (stay on the first one)",
        -1 to "Every time the screen turns on",
        30 to "Every 30 seconds",
        60 to "Every minute",
        300 to "Every 5 minutes",
        1800 to "Every 30 minutes"
    )

    private fun updateSwitchButtonText() {
        val label = switchChoices.firstOrNull { it.first == prefs.getMiniSwitchSecs() }?.second ?: "Never"
        binding.btnMiniSwitch.text = "Change: ${label.replaceFirstChar { it.lowercase() }}"
    }

    private fun showSwitchDialog() {
        val current = switchChoices.indexOfFirst { it.first == prefs.getMiniSwitchSecs() }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Change to the next one")
            .setSingleChoiceItems(switchChoices.map { it.second }.toTypedArray(), current) { dialog, which ->
                prefs.setMiniSwitchSecs(switchChoices[which].first)
                updateSwitchButtonText()
                MiniPlayerController.sync(this)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePauseBelowText(percent: Int) {
        binding.tvMiniPauseBelow.text =
            if (percent <= 0) "Pause when battery is below: off" else "Pause when battery is below: $percent%"
    }

    // ---------------------------------------------------------------- Permissions

    private fun buildPermissionRows() {
        val box = binding.llPermissions
        box.removeAllViews()

        addPermissionRow(
            box, "Display over other apps", "Needed for the mini player",
            Settings.canDrawOverlays(this)
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addPermissionRow(
            box, "Usage access", "Lets the mini player hide outside the home screen",
            MiniPlayerController.hasUsageAccess(this)
        ) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        addPermissionRow(
            box, "Accessibility service", "Hide in the app drawer, show on the lock screen",
            MiniPlayerController.hasAccessibility(this)
        ) {
            Toast.makeText(
                this,
                "Turn on 'Battery Chan mini player'. If it is greyed out, open App info > menu > Allow restricted settings first.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addPermissionRow(
            box, "Notifications", "Pop-ups, speed meter and the ongoing notification",
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        addPermissionRow(
            box, "Battery optimisation", "Set to unrestricted so Android does not stop the mini player",
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        ) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun addPermissionRow(box: LinearLayout, title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        })
        texts.addView(TextView(this).apply {
            text = why
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (granted) {
            row.addView(TextView(this).apply {
                text = "\u2713 Granted"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
                setPadding(dp(8), 0, 0, 0)
            })
        } else {
            row.addView(MaterialButton(this).apply {
                text = "Grant"
                setOnClickListener { onGrant() }
            })
        }
        box.addView(row)
    }

    // ---------------------------------------------------------------- Backup

    private fun setupBackup() {
        binding.btnBackupExport.setOnClickListener { exportLauncher.launch("Battery-chan-backup.zip") }
        binding.btnBackupImport.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Import backup?")
                .setMessage("This replaces all current settings, events and media with the ones in the backup file.")
                .setPositiveButton("Choose file") { _, _ -> importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun runBackup(uri: Uri, export: Boolean) {
        val app = applicationContext
        Thread {
            val message = try {
                if (export) {
                    val out = app.contentResolver.openOutputStream(uri) ?: error("Could not write the file")
                    Backup.export(app, out)
                    "Backup saved."
                } else {
                    val input = app.contentResolver.openInputStream(uri) ?: error("Could not read the file")
                    val ok = input.use { Backup.import(app, it) }
                    if (ok) "Backup restored." else "That file is not a Battery Chan backup."
                }
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (!export && message == "Backup restored.") {
                    MiniPlayerController.sync(this)
                    recreate()
                }
            }
        }.start()
    }

    private fun updateMiniDrawerUi() {
        val hideDrawer = prefs.isMiniHideDrawer()
        val showLock = prefs.isMiniShowOnLock()
        val granted = MiniPlayerController.hasAccessibility(this)
        binding.btnMiniAccessibility.visibility =
            if ((hideDrawer || showLock) && !granted) View.VISIBLE else View.GONE
        binding.tilMiniDrawerMarker.visibility = if (hideDrawer) View.VISIBLE else View.GONE
        binding.tvMiniDrawerStatus.text = when {
            !hideDrawer -> "The mini player also shows in the app drawer."
            granted -> "It hides itself while your app drawer is open."
            else -> "Needs the 'Battery Chan mini player' accessibility service. It only looks at your " +
                "launcher to tell the home screen from the app drawer."
        }
        binding.switchMiniLockLockscreen.visibility = if (showLock) View.VISIBLE else View.GONE
        binding.tvMiniLockStatus.text = when {
            !showLock -> "The mini player is hidden while the phone is locked."
            granted -> "It also shows on the lock screen, with its own position and size. Turn off " +
                "'Lock position and size (lock screen)', lock the phone, then drag or resize it there. " +
                "It is saved and comes back the next time you lock the phone. Some phones do not allow this."
            else -> "Android hides normal overlays on the lock screen, so this needs the " +
                "'Battery Chan mini player' accessibility service."
        }
    }

    private fun updateMiniUsageUi() {
        val homeOnly = prefs.isMiniHomeOnly()
        val granted = MiniPlayerController.hasUsageAccess(this)
        binding.btnMiniUsage.visibility = if (homeOnly && !granted) View.VISIBLE else View.GONE
        binding.tvMiniUsageStatus.text = when {
            !homeOnly -> "It stays on top of every app."
            granted -> "It hides itself while another app is open."
            else -> "To know when the home screen is open, Android needs Usage access. " +
                "Until you grant it, the mini player stays on top of every app."
        }
    }

    // ---------------------------------------------------------------- Custom events

    private fun showEventDialog(existing: CustomEvent?) {
        val dialogBinding = DialogCustomEventBinding.inflate(layoutInflater)

        dialogBinding.etName.setText(existing?.name)
        dialogBinding.sliderLevel.value = (existing?.level ?: 80).toFloat()

        fun updateLevelLabel() {
            dialogBinding.tvLevel.text = "Battery level: ${dialogBinding.sliderLevel.value.toInt()}%"
        }
        updateLevelLabel()
        dialogBinding.sliderLevel.addOnChangeListener { _, _, _ -> updateLevelLabel() }

        val initialButton = when (existing?.trigger ?: Trigger.RISING) {
            Trigger.RISING -> dialogBinding.btnTriggerRising
            Trigger.FALLING -> dialogBinding.btnTriggerFalling
            Trigger.BOTH -> dialogBinding.btnTriggerBoth
        }
        dialogBinding.toggleTrigger.check(initialButton.id)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New event" else "Edit event")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val level = dialogBinding.sliderLevel.value.toInt()
                val trigger = when (dialogBinding.toggleTrigger.checkedButtonId) {
                    dialogBinding.btnTriggerFalling.id -> Trigger.FALLING
                    dialogBinding.btnTriggerBoth.id -> Trigger.BOTH
                    else -> Trigger.RISING
                }
                val name = dialogBinding.etName.text?.toString()?.trim().orEmpty()
                    .ifEmpty { "$level% event" }

                val event = existing?.copy(name = name, level = level, trigger = trigger)
                    ?: CustomEvent(name = name, level = level, trigger = trigger)
                prefs.saveCustomEvent(event)
                buildEventList()

                if (existing == null) {
                    Toast.makeText(
                        this,
                        "Event added. Pick a sound or an image / GIF / video for it below.",
                        Toast.LENGTH_LONG
                    ).show()
                    eventViews[event.key]?.root?.let { card ->
                        binding.scrollView.post {
                            binding.scrollView.smoothScrollTo(0, binding.containerEvents.top + card.top)
                        }
                    }
                }
            }
            .show()
    }

    private fun confirmDelete(event: CustomEvent) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"${event.name}\"?")
            .setMessage("Its sound, image/video and message will be removed too.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                FileManager.deleteEventFiles(this, event.key)
                prefs.clearEvent(event.key)
                prefs.deleteCustomEvent(event.id)
                buildEventList()
            }
            .show()
    }

    // ---------------------------------------------------------------- General

    private fun setupGlobalSettings() {
        binding.switchGlobalEvents.isChecked = prefs.isGlobalEventsEnabled()
        binding.switchGlobalEvents.setOnCheckedChangeListener { _, isChecked ->
            prefs.setGlobalEventsEnabled(isChecked)
        }

        binding.switchAutoDismissAudio.isChecked = prefs.isAutoDismissOnAudio()
        binding.switchAutoDismissAudio.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoDismissOnAudio(isChecked)
        }

        binding.switchBoostVolume.isChecked = prefs.isBoostVolume()
        binding.switchBoostVolume.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoostVolume(isChecked)
        }

        fun updateDurationLabel(seconds: Int) {
            binding.tvDurationLabel.text = "Pop-up duration: $seconds s"
        }
        val duration = prefs.getNotificationDuration().coerceIn(5, 30)
        binding.sliderDuration.value = duration.toFloat()
        updateDurationLabel(duration)
        binding.sliderDuration.addOnChangeListener { _, value, _ ->
            prefs.setNotificationDuration(value.toInt())
            updateDurationLabel(value.toInt())
        }
    }

    // --- Power indicator settings (ported from Wattz) ---

    private fun setupPowerIndicatorSettings() {
        binding.switchPowerIndicator.isChecked = prefs.isPowerIndicatorEnabled()
        binding.switchPowerIndicator.setOnCheckedChangeListener { _, isChecked ->
            prefs.setPowerIndicatorEnabled(isChecked)
            notifyServiceOfMetricsChange()
        }

        val checkedUnitChip = when (prefs.getIndicatorUnits()) {
            "Ah" -> binding.chipUnitAh
            "C" -> binding.chipUnitC
            "V" -> binding.chipUnitV
            "Wh" -> binding.chipUnitWh
            "%" -> binding.chipUnitPercent
            else -> binding.chipUnitV
        }
        binding.chipGroupUnits.check(checkedUnitChip.id)
        binding.chipGroupUnits.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val units = when (id) {
                binding.chipUnitAh.id -> "Ah"
                binding.chipUnitC.id -> "C"
                binding.chipUnitV.id -> "V"
                binding.chipUnitWh.id -> "Wh"
                binding.chipUnitPercent.id -> "%"
                else -> "V"
            }
            prefs.setIndicatorUnits(units)
            notifyServiceOfMetricsChange()
        }
    }

    // Lets a currently-running BatteryService pick up the new units/invert settings
    // immediately instead of waiting for its next restart.
    private fun notifyServiceOfMetricsChange() {
        sendBroadcast(
            Intent(BatteryService.ACTION_METRICS_SETTINGS_CHANGED).setPackage(packageName)
        )
    }

    private companion object {
        const val STATE_PENDING_KEY = "pending_key"
        const val STATE_PENDING_TYPE = "pending_type"
    }
}
