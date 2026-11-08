package com.tg.batterychan.data

/** Built-in events that ship with the app. Users can add their own via [CustomEvent]. */
enum class BatteryEvent(val key: String, val displayName: String, val description: String) {
    LOW_BATTERY("low_battery", "Low Battery (≤20%)", "When the battery drops to 20%"),
    CHARGING_CONNECTED("charging_connected", "Charging Connected", "When a charger is plugged in"),
    FULL_CHARGE("full_charge", "Full Charge (100%)", "When charging reaches 100%"),
    CHARGER_UNPLUGGED("charger_unplugged", "Charger Unplugged", "When the charger is removed")
}
