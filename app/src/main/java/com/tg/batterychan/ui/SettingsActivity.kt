package com.tg.batterychan.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.tg.batterychan.data.BatteryEvent
import com.tg.batterychan.data.FileManager
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.databinding.ActivitySettingsBinding
import com.tg.batterychan.databinding.ItemEventSettingsBinding
import com.tg.batterychan.service.BatteryService
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    
    private var pendingEvent: BatteryEvent? = null
    private var pendingType: Prefs.FileType? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleFilePicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = Prefs(this)
        
        setupToolbar()
        setupSections()
        setupGlobalSettings()
        setupPowerIndicatorSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupSections() {
        setupEventSection(binding.sectionLowBattery, BatteryEvent.LOW_BATTERY)
        setupEventSection(binding.sectionCharging, BatteryEvent.CHARGING_CONNECTED)
        setupEventSection(binding.sectionFullCharge, BatteryEvent.FULL_CHARGE)
        setupEventSection(binding.sectionUnplugged, BatteryEvent.CHARGER_UNPLUGGED)
    }

    private fun setupEventSection(itemBinding: ItemEventSettingsBinding, event: BatteryEvent) {
        itemBinding.tvEventTitle.text = event.displayName
        
        updateSectionUI(itemBinding, event)

        itemBinding.etEventText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.setEventText(event, s?.toString())
            }
        })

        itemBinding.btnPickAudio.setOnClickListener {
            pendingEvent = event
            pendingType = Prefs.FileType.AUDIO
            pickFileLauncher.launch(arrayOf("audio/*"))
        }

        itemBinding.btnPickImage.setOnClickListener {
            pendingEvent = event
            pendingType = Prefs.FileType.IMAGE
            pickFileLauncher.launch(arrayOf("image/*"))
        }

        itemBinding.btnRemoveAudio.setOnClickListener {
            removeFile(event, Prefs.FileType.AUDIO)
            updateSectionUI(itemBinding, event)
        }

        itemBinding.btnRemoveImage.setOnClickListener {
            removeFile(event, Prefs.FileType.IMAGE)
            updateSectionUI(itemBinding, event)
        }
        
        itemBinding.btnTestEvent.setOnClickListener {
            testEvent(event)
        }
    }

    private fun testEvent(event: BatteryEvent) {
        val audioPath = prefs.getFilePath(event, Prefs.FileType.AUDIO)
        val imagePath = prefs.getFilePath(event, Prefs.FileType.IMAGE)
        val customText = prefs.getEventText(event)
        
        // Play audio
        if (audioPath != null && File(audioPath).exists()) {
            try {
                android.media.MediaPlayer().apply {
                    setDataSource(audioPath)
                    prepare()
                    start()
                    setOnCompletionListener { release() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Show notification preview
        showPreviewNotification(event, imagePath, customText)

        // Show actual PopUpActivity for testing
        val intent = Intent(this, PopUpActivity::class.java).apply {
            putExtra("event_title", event.displayName)
            putExtra("image_path", imagePath)
            putExtra("audio_path", audioPath)
            putExtra("custom_text", customText)
            putExtra("duration", prefs.getNotificationDuration())
        }
        startActivity(intent)
        
        android.widget.Toast.makeText(this, "Testing: ${event.displayName}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showPreviewNotification(event: BatteryEvent, imagePath: String?, customText: String?) {
        val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "battery_events_channel"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Battery Events", android.app.NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.tg.batterychan.R.drawable.ic_battery_alert)
            .setContentTitle("Notification Preview")
            .setContentText(customText ?: event.displayName)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (imagePath != null && File(imagePath).exists()) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(imagePath)
            builder.setStyle(androidx.core.app.NotificationCompat.BigPictureStyle().bigPicture(bitmap))
        }

        notificationManager.notify(event.ordinal + 100, builder.build())
    }

    private fun removeFile(event: BatteryEvent, type: Prefs.FileType) {
        val path = prefs.getFilePath(event, type)
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
        prefs.setFilePath(event, type, null)
    }

    private fun updateSectionUI(itemBinding: ItemEventSettingsBinding, event: BatteryEvent) {
        val audioPath = prefs.getFilePath(event, Prefs.FileType.AUDIO)
        val imagePath = prefs.getFilePath(event, Prefs.FileType.IMAGE)
        val customText = prefs.getEventText(event)

        if (itemBinding.etEventText.text?.toString() != (customText ?: "")) {
            itemBinding.etEventText.setText(customText)
        }

        itemBinding.tvAudioName.text = audioPath?.let { 
            val info = FileManager.getFileInfo(it)
            if (info != null) {
                "${info.name} (${info.size / 1024} KB)"
            } else {
                File(it).name
            }
        } ?: "No audio selected"
        itemBinding.btnRemoveAudio.visibility = if (audioPath != null) View.VISIBLE else View.GONE

        itemBinding.tvImageName.text = imagePath?.let { 
            val info = FileManager.getFileInfo(it)
            if (info != null) {
                "${info.name} (${info.size / 1024} KB)"
            } else {
                File(it).name
            }
        } ?: "No image selected"
        itemBinding.btnRemoveImage.visibility = if (imagePath != null) View.VISIBLE else View.GONE
        
        if (imagePath != null) {
            itemBinding.ivPreview.visibility = View.VISIBLE
            Glide.with(this).load(imagePath).into(itemBinding.ivPreview)
        } else {
            itemBinding.ivPreview.visibility = View.GONE
        }
    }

    private fun handleFilePicked(uri: Uri) {
        val event = pendingEvent ?: return
        val type = pendingType ?: return
        
        val result = FileManager.saveFile(this, uri, event, type)
        when (result) {
            is FileManager.SaveResult.Success -> {
                prefs.setFilePath(event, type, result.path)
                refreshUI()
            }
            is FileManager.SaveResult.Error -> {
                android.widget.Toast.makeText(this, result.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshUI() {
        setupEventSection(binding.sectionLowBattery, BatteryEvent.LOW_BATTERY)
        setupEventSection(binding.sectionCharging, BatteryEvent.CHARGING_CONNECTED)
        setupEventSection(binding.sectionFullCharge, BatteryEvent.FULL_CHARGE)
        setupEventSection(binding.sectionUnplugged, BatteryEvent.CHARGER_UNPLUGGED)
    }

    private fun setupGlobalSettings() {
        binding.switchGlobalEvents.isChecked = prefs.isGlobalEventsEnabled()
        binding.switchGlobalEvents.setOnCheckedChangeListener { _, isChecked ->
            prefs.setGlobalEventsEnabled(isChecked)
        }

        binding.switchAutoDismissAudio.isChecked = prefs.isAutoDismissOnAudio()
        binding.switchAutoDismissAudio.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoDismissOnAudio(isChecked)
        }

        binding.sliderDuration.value = prefs.getNotificationDuration().toFloat()
        binding.sliderDuration.addOnChangeListener { _, value, _ ->
            prefs.setNotificationDuration(value.toInt())
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
        binding.chipGroupUnits.setOnCheckedStateChangeListener { group, checkedIds ->
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

    // Lets a currently-running BatteryService pick up the new scalar/units/invert
    // settings immediately instead of waiting for its next restart.
    private fun notifyServiceOfMetricsChange() {
        sendBroadcast(
            Intent(BatteryService.ACTION_METRICS_SETTINGS_CHANGED).setPackage(packageName)
        )
    }
}
