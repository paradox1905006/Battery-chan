# Battery Chan — merged edition

This project merges **Wattz** (by dubrowgn) into **Battery Chan** (by Paradox).
Battery Chan is the base app (package `com.tg.batterychan`); Wattz's detailed
battery-metrics engine has been ported in as a new feature, not just copied
alongside it.

## What Battery Chan already had (kept as-is)
- Circular battery percentage + charging status on the home screen
- Per-event popups (Low Battery, Charging Connected, Full Charge, Charger
  Unplugged) with a custom image, custom audio, and custom text per event
- Foreground "monitor" service with restart-on-kill behavior
- Battery-optimization warning banner, notification permission handling

## What was ported in from Wattz
- `metrics/Battery.kt`, `BatterySnapshot.kt`, `PrettyFormat.kt`, `Extensions.kt`,
  `PeriodicTask.kt` — the engine that reads `BatteryManager` and turns it into
  power / current / voltage / energy / temperature / time-to-full-charge.
- **Detailed Stats card** on the home screen (MainActivity) showing all of the
  above, refreshed every 1.25s while the app is open.
- **Live status bar power indicator** (optional, off by default) — renders the
  current reading directly into the notification icon, the way Wattz's status
  bar indicator worked. Toggle it and pick units (W/A/V/Wh/Ah/%/°C) in
  Settings → Power Indicator.
- **Power Scalar Workaround** and **Charging Workaround** settings, for phones
  (notably some Samsungs) that misreport `BatteryManager` values.

## Design decisions in the merge
- Detailed stats in `MainActivity` are computed locally (a `Battery` instance
  in the activity itself) rather than over the old Wattz IPC broadcast,
  because Battery Chan's service is optional (user-toggled), so the stats
  card needs to work whether or not the background monitor is running.
- The background `BatteryService` only runs the metrics polling loop when the
  live power indicator is enabled, to avoid the extra battery drain if nobody
  wants that feature (there's some irony in a battery app draining battery).
- Both notification channels are kept separate: `battery_events_channel`
  (existing high-importance event alerts) and the new `power_status_channel`
  (low-importance, ongoing, for the optional live indicator).

## Not carried over from Wattz
- Wattz's separate `:batteryStatus` process — unnecessary here since Battery
  Chan's service already runs in the main process.
- Wattz's own UI shell/theme — Battery Chan's Material 3 dark theme is used
  throughout instead.

## v9 — speed meter fixes
- Status bar icon: the speed notification now draws "download / upload" as a two-line icon (like 16K / 4K) in the status bar.
- Auto colour (on by default): the speed text takes the most prominent bright colour of the whole video (e.g. Miku teal hair). Switch it off in Settings to use a fixed colour.
- Speed meter in the mini player: bigger touch area, drag to move, pinch with two fingers on the meter to resize it.
- Speed-only service no longer stops when the mini player has an empty playlist; unit (bytes/bits) is applied in notification-only mode too.
- Turning on "Show in the notification panel" asks for notification permission on Android 13+.

## v11
- The status bar speed icon is now its own ongoing notification (channel "Speed meter"), built exactly like the power indicator, instead of the icon of the foreground-service notification.
- Pinching the speed meter inside the mini player now really resizes the meter (before, the player's own pinch grabbed the gesture first). Size range 8-40 sp. If the text does not fit on one line it wraps into two lines (down above up) before it shrinks.
