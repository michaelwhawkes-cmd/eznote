# EZ Note: native Android app + your own sign-in server

Accounts live on **your server** (`server/`), not with Google. Users sign in with a **passkey** (fingerprint / face / screen lock)
or a username + password. Notes, PDFs and PDF markup sync across devices through the same server. The server has no dependencies.

## 1. Run and test the server (Node 18+)
    cd server
    node test.js          # 26 checks, including a simulated passkey authenticator
    RP_ID=localhost node server.js

## 2. Deploy it (passkeys need HTTPS on a domain you own, e.g. notes.yourdomain.com)
Any host that runs Node or Docker works (Render, Railway, Fly.io, a VPS with Caddy). Set these environment variables:

| Variable | Value |
|---|---|
| `RP_ID` | your domain, no https://, e.g. `notes.yourdomain.com` |
| `ANDROID_PACKAGE` | `com.example.notes` |
| `ANDROID_SHA256` | SHA-256 fingerprint(s) of your app's signing key, comma-separated (see step 3) |
| `DATA_DIR` | a **persistent disk** folder; users, notes and files live here (back it up) |
| `TRUST_PROXY` | `1` when behind a proxy (hosts above) |
| `SECRET` | optional; session-signing key (auto-generated into `DATA_DIR` if empty) |

The server publishes `https://YOUR_DOMAIN/.well-known/assetlinks.json` automatically. Android checks it before allowing passkeys.

## 3. Build the app
1. Make a signing key once (passkeys are tied to it): `keytool -genkeypair -v -keystore ez.jks -alias ez -keyalg RSA -keysize 2048 -validity 36500 -storepass YOURPASS -keypass YOURPASS`
2. Put `ez.jks` next to `settings.gradle.kts`, copy `keystore.properties.example` to `keystore.properties`, fill in the passwords.
3. Get the fingerprint: `keytool -list -v -keystore ez.jks -alias ez` and copy the **SHA256** line into the server's `ANDROID_SHA256`.
4. In `app/src/main/java/com/example/notes/Config.kt` set `SERVER` to `https://YOUR_DOMAIN`.
5. Open the folder in Android Studio and **Build > Build APK(s)**, or run on a phone.
   (GitHub build: add repository secrets `KEYSTORE_BASE64` = `base64 -w0 ez.jks` and `KEYSTORE_PASSWORD`.)

## If something fails
* "This app isn't registered with the server (origin android:apk-key-hash:...)": the fingerprint in `ANDROID_SHA256` doesn't match the key that signed the APK.
* "No passkey found" / "Couldn't create the passkey": open `https://YOUR_DOMAIN/.well-known/assetlinks.json` in a browser; it must show your package and fingerprint, and `RP_ID` must equal that domain.
* "Can't reach the server": check `Config.SERVER` and that the server uses HTTPS.

## Good to know
* Passkeys sync between a user's Android devices through Google Password Manager. There is no email or password reset, so users should add a password or a second passkey (menu > Add a passkey) as a backup.
* Sessions last 90 days. Notes sync on app start and when you tap Sync. A note deleted on one device is deleted everywhere.
