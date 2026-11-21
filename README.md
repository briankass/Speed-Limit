# Speed Alert overlay

A small Android app that floats a speed-limit bubble on top of Google Maps (or any app).

- Shows the **posted speed limit** and **your current speed** (mph)
- **Limit changes**: the sign flashes "NEW", with a beep, voice ("Speed limit 35") and/or vibration
- **Speeding**: warns when you go over the limit by **+0 / +5 / +10 / +15 mph** (your choice)
  - Green = at or under the limit, amber = over but within your tolerance, red = speeding
  - Waits 2 seconds and gives 2 mph of slack so it doesn't keep beeping on and off
  - Gives you 6 seconds to slow down after the limit drops
  - Optional repeat every 30 seconds while still speeding
- Speed limits come from **OpenStreetMap** (free, no account). Roads with no data show `--`.

## Installing on your phone

1. Open this repo's **Releases** page on your phone (github.com/<you>/<repo>/releases).
2. Tap **SpeedAlert.apk** under the newest release to download it.
3. Open the download. Android will ask you to allow installs from your browser: allow it.
4. Open **Speed Alert**, choose your settings, tap **Start overlay** and grant:
   - Location ("While using the app")
   - Notifications
   - "Display over other apps"
5. Open Google Maps. Drag the bubble wherever you want it.

Stop it from the app or with the **Stop** button in the notification.

New builds install over the old one, and your settings are kept.

## Tips
- If Android closes the app in the background, go to Settings → Apps → Speed Alert → Battery and choose **Unrestricted**.
- Use **Test alerts** to check volume. Beeps play on the media volume, so they come through the car speakers over Bluetooth.

## How it builds
Every push to `main` runs `.github/workflows/build.yml`, which builds the APK and publishes it as a release.

Speed limit data © OpenStreetMap contributors. Always obey the posted signs.
