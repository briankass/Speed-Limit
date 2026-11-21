# Speed Alert overlay

A small Android app that floats a speed-limit bubble on top of Google Maps (or any app).

- Shows the **posted speed limit** and **your current speed** (mph)
- **Limit changes**: the sign flashes "NEW", with a beep, voice ("Speed limit 35") and/or vibration
- **Speeding**: warns when you go over the limit by **+0 / +5 / +10 / +15 mph** (your choice)
  - Green = at or under the limit, amber = over but within your tolerance, red = speeding
  - Waits 2 seconds and gives 2 mph of slack so it doesn't keep beeping on and off
  - Gives you 6 seconds to slow down after the limit drops
  - Optional repeat every 30 seconds while still speeding
- Speed limits from your choice of **OpenStreetMap** (free, no key), **HERE** or **TomTom** (free API keys)
  - With HERE/TomTom you can fall back to OpenStreetMap when they have no data
  - A small label under the limit shows which source it came from (OSM / HERE / TOMTOM)
  - Roads with no data show `--`

## Getting a HERE or TomTom key
- **TomTom**: sign up at https://developer.tomtom.com, copy the API key from your dashboard. Free plan: 2,500 lookups/day.
- **HERE**: sign up at https://platform.here.com, create an app, then create an API key.

In the app, pick HERE or TomTom under *Speed limit data*, paste the key, and tap **Test key**.
Keys are stored only on your phone. The app asks for a new limit about every 200 m (or when you turn), so a typical hour of driving uses a few hundred lookups.

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
