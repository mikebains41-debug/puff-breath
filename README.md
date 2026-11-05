# Puff Breath

Puff, steered by breathing. An Android app shows the live Puff site in a WebView,
measures breathing with the Presage SmartSpectra SDK, and feeds the readings into
the game. Breathe in to rise, breathe out to sink; slower breathing slows the pipes.

```
Camera -> SmartSpectra (Android) -> evaluateJavascript -> window.PuffBreath (game) -> fish moves
```

The website keeps working as before: nothing feeds `PuffBreath` there, so
`PuffBreath.active()` stays false and Puff uses tap controls.

## Files

| File | What it does |
| --- | --- |
| `app/.../MainActivity.kt` | WebView host, camera permission, SmartSpectra session, sends readings to the page |
| `web/puff-breath.js` | Game-side receiver: scales the breathing wave, decides when breath control is on |
| `web/puff-breath.test.js` | Tests for the receiver (`node --test web/puff-breath.test.js`) |
| `.github/workflows/build-apk.yml` | Builds the APK on GitHub, so no Android Studio is needed |

## 1. Build the app (all from Termux)

1. Get a free API key at <https://physiology.presagetech.com/auth/register>.
2. Create a GitHub repo and push this folder to it.
3. In the repo: **Settings → Secrets and variables → Actions → New repository secret**,
   name `SMARTSPECTRA_API_KEY`, value = your key. The key never goes in the code.
4. Every push to `main` runs **Build APK**. Open the run, download the
   `puff-breath-debug-apk` artifact, unzip it, and install the APK on your phone.

The key is compiled into the APK, which is fine for the challenge. Before publishing
the app, switch to SmartSpectra's OAuth setup so the key isn't shipped.

## 2. Add the receiver to the Puff site

1. Copy `web/puff-breath.js` into the Puff site folder.
2. Load it before the game script:
   ```html
   <script src="puff-breath.js"></script>
   ```
3. In the game's update loop, use breath when it's on and taps when it isn't.
   Rename `fish`, `TOP_Y`, `BOTTOM_Y` and `BASE_PIPE_SPEED` to match your code:
   ```js
   if (PuffBreath.active()) {
     // Ease the fish toward the height that matches the breath.
     const target = PuffBreath.targetY(TOP_Y, BOTTOM_Y);
     fish.y += (target - fish.y) * Math.min(1, dt * 6); // dt in seconds
     fish.vy = 0;
   } else {
     // existing tap + gravity code, unchanged
   }
   pipeSpeed = BASE_PIPE_SPEED * PuffBreath.speedFactor();

   if (PuffBreath.calibrating()) {
     // show: "Breathe normally while it tunes in"
   }
   ```
4. Hide the ad slot inside the app. Google restricts AdSense inside app WebViews:
   ```css
   .puff-in-app .ad-slot { display: none; }
   ```
5. Deploy to Netlify as usual.

## 3. Play

The breathing signal needs the right setup (from Presage's model card):

- Prop the phone up; a handheld camera doesn't work.
- Sit still with your face and upper chest in view, in good light.
- Avoid dark or tightly striped clothing, and don't talk.
- The first ~30 seconds are calibration. The fish switches to breath control
  once the SDK marks the signal stable.

If the fish moves the wrong way (rises when you breathe out), call
`PuffBreath.setInvert(true)` once at startup.

Test the delay before building gameplay around it. If the fish lags your breath
too much to dodge pipes, keep `speedFactor()` and drop the steering.

## Security notes

- Data only flows native → page through `evaluateJavascript`. There is no
  `addJavascriptInterface`, so the page and any ad in it can't call native code.
- Readings are only sent while the WebView is on `https://puff-game.netlify.app`.
  Any other link opens in the browser instead of the app.
- The page is denied camera and microphone access; only the SDK uses the camera.
- Only numbers and booleans are sent to the page, no strings from the SDK.
