# Battery Chan

![Battery Chan Logo](app/src/main/res/drawable/logo_png.png)

**Battery Chan** is a feature-rich Android battery monitoring application that combines aesthetics with deep technical insights. Featuring custom alerts, anime-themed visuals, and a live status bar indicator, it ensures you always know exactly what\'s happening with your device\'s power.

## ✨ Features

- **Detailed Battery Stats**: Real-time monitoring of Voltage, Energy (Wh/Ah), and Temperature.
- **Anime Alerts**: Personalize your battery milestones (Low Battery, Charging Connected, Full Charge, Unplugged) with custom:
    - 🖼️ Images
    - 🎵 Audio / Sound effects
    - 💬 Custom Text
- **Live Status Bar Indicator**: A ported and optimized power indicator that shows your battery\'s status directly in the status bar icon area.
- **Pop-up Notifications**: Eye-catching pop-up alerts that appear over other apps when battery events occur.
- **Deep Monitoring**: Keeps track of "Charging Since" and "Time to Full" estimates.

## 🚀 Getting Started

1.  **Enable Monitor**: Tap the main button to start the background service.
2.  **Personalize**: Go to "Personalize Events" to set up your own sounds and images for different battery states.
3.  **Optimize**: Click the battery optimization warning at the bottom to ensure the app isn\'t killed by Android\'s power management for consistent monitoring.

## 🛠 Technical Details

- **Version**: 1.0.6 (Build 106)
- **Min SDK**: 29 (Android 10)
- **Target SDK**: 36 (Android 15)
- **Developed by**: Paradox
- **Ported Logic**: Detailed metrics engine and status bar rendering adapted from the Wattz project.

## ⚖️ Permissions

To provide full functionality, the app requires:
- `POST_NOTIFICATIONS`: To show event alerts and the status bar indicator.
- `SYSTEM_ALERT_WINDOW`: To display pop-up alerts over other applications.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: To keep the monitor running accurately in the background.

---
*Made with ⚡ by Paradox*
