package com.tg.batterychan.data

enum class BatteryEvent(val key: String, val displayName: String) {
    LOW_BATTERY("low_battery", "Low Battery (≤20%)"),
    CHARGING_CONNECTED("charging_connected", "Charging Connected"),
    FULL_CHARGE("full_charge", "Full Charge (100%)"),
    CHARGER_UNPLUGGED("charger_unplugged", "Charger Unplugged")
}
