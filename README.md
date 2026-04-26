# MW Alert — Android APK

Native Android app that polls your MW Alert scraper 24/7 and sends notifications even when the app is fully closed. Uses a foreground service + wake lock to bypass Android's background restrictions.

## ✅ What this app does

- Connects to your MW Alert scraper via ngrok
- Polls every 6 seconds for new matched jobs
- Sends Android system notifications with sound + vibration
- **Keeps running even when you swipe the app away**
- **Restarts automatically after device reboot**
- Each new job = its own buzz/notification on your phone

## 🚀 How to build the APK (no coding needed)

### Step 1 — Create a free GitHub account
1. Go to https://github.com → Sign up
2. Verify your email

### Step 2 — Create a new repository
1. Click the **+** icon (top right) → **New repository**
2. Name it: `mwalert-android`
3. Set to **Public** (private also works but free Actions minutes are limited on private)
4. ❌ **Don't** initialize with README (we'll upload our own)
5. Click **Create repository**

### Step 3 — Upload these files to GitHub
You have 2 ways:

**Option A — Web upload (easiest):**
1. On the empty repo page, click **"uploading an existing file"**
2. Drag and drop **ALL the files and folders** from this APK package
3. Make sure you upload the **entire folder structure** intact
4. Scroll down → write "initial commit" → click **Commit changes**

**Option B — Git command line (if you know how):**
```bash
git init
git add .
git commit -m "initial commit"
git remote add origin https://github.com/YOUR_USERNAME/mwalert-android.git
git push -u origin main
```

### Step 4 — Wait for GitHub to build the APK
1. After uploading, click the **Actions** tab on your repo
2. You'll see a workflow running called "Build APK"
3. Wait 5-10 minutes for it to finish (green checkmark)

### Step 5 — Download the APK
1. Click the completed workflow run
2. Scroll down to **Artifacts** section
3. Click **mwalert-debug-apk** to download a ZIP
4. Extract → you'll find `app-debug.apk`

### Step 6 — Install on your Android phone
1. Email or transfer the APK to your phone (Google Drive, USB, WhatsApp to yourself, etc.)
2. Tap the APK on your phone
3. Android will warn "unknown source" — go to **Settings → Apps → Special access → Install unknown apps** → enable for the file manager you used
4. Tap APK again → Install

### Step 7 — First-time app setup
1. Open MW Alert on your phone
2. Login with:
   - **Server URL**: your ngrok URL (e.g. `triage-sculpture-wool.ngrok-free.dev`)
   - **Email**: `admin@mwalert.com`
   - **Password**: `admin123`
3. Tap **Sign In**
4. Grant **notification permission** when asked
5. Tap **Disable Battery Optimization** button — this is critical for 24/7 background!
6. Done — you'll see the persistent "MW Alert is monitoring" notification

## ⚙️ Key files in this project

| File | Purpose |
|---|---|
| `MainActivity.java` | The login + dashboard screen |
| `PollingService.java` | The 24/7 background polling engine |
| `ApiHelper.java` | HTTP client for talking to scraper |
| `BootReceiver.java` | Auto-restart after device reboot |
| `AndroidManifest.xml` | Permissions (notifications, foreground service, etc.) |
| `.github/workflows/build.yml` | GitHub Actions config that builds the APK |

## 🛠️ Updating the app later

Whenever you want to change something (e.g., update poll interval):
1. Edit the file on GitHub (click the file → pencil icon)
2. Commit changes
3. GitHub Actions rebuilds the APK in 5-10 min
4. Download new APK from Actions tab
5. Install on phone (overwrites old version, keeps your login)

## 🚨 Important: Battery optimization

Android aggressively kills background services to save battery. **You MUST whitelist the app:**

1. **In the app**: tap "Disable Battery Optimization" button (or Settings → Apps → MW Alert → Battery → Unrestricted)
2. **For Xiaomi/Redmi/Poco**: also go to Settings → Apps → MW Alert → Battery saver → No restrictions, AND enable "Autostart"
3. **For Oppo/Realme**: Settings → Battery → Power consumption details → MW Alert → Allow background activity
4. **For Samsung**: Settings → Apps → MW Alert → Battery → Unrestricted, AND Settings → Battery → Background usage limits → Never sleeping apps → add MW Alert

Without these settings, Android will kill the polling service after a few minutes.

## ⚠️ What this app cannot do

- ❌ Run when your **PC is off** (it polls the scraper which runs on your PC)
- ❌ Run if **ngrok URL changed** — you'd need to logout and log back in with the new URL
- ❌ Run if you **deny notification permission** or battery whitelist
- ❌ Bypass aggressive Chinese OEM battery savers without manual whitelist

## 🔐 Security notes

- The app uses HTTPS to ngrok (encrypted)
- Auth token stored in private SharedPreferences (not accessible by other apps)
- Cleartext HTTP allowed only as fallback for non-ngrok local IPs
- No analytics, no ads, no data collection

## 📞 Support

If the app crashes or doesn't connect:
1. Check that your scraper is running on PC
2. Check that ngrok is running
3. Logout and re-login with current ngrok URL
4. Make sure battery optimization is disabled
