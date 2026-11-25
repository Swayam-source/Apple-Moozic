# Apple Moozic (BitChord UI + Discord Social SDK) — Setup Guide

## 1. Discord Developer Portal Setup
1. Open the [Discord Developer Portal](https://discord.com/developers/applications) and create/select your application.
2. Copy your **Application ID** (Client ID).
3. Under **OAuth2 → Redirects**, add:
   `applemoozic://discord/callback`
4. Enable **Public Client** under OAuth2 and save changes.
5. In your GitHub repo, add `DISCORD_CLIENT_ID` under **Settings → Secrets and variables → Actions**.

## 2. Adding Official Discord Social SDK Binaries (Optional)
The C++ JNI bridge (`app/src/main/cpp/discord_bridge.cpp`) compiles cleanly out-of-the-box on GitHub Actions even before proprietary SDK files are added. To enable live SDK transport:
- Place `discord_partner_sdk.h` in `app/src/main/cpp/include/discord_partner_sdk.h`
- Place `libdiscord_partner_sdk.so` in:
  - `app/src/main/jniLibs/arm64-v8a/libdiscord_partner_sdk.so`
  - `app/src/main/jniLibs/armeabi-v7a/libdiscord_partner_sdk.so`
  - `app/src/main/jniLibs/x86_64/libdiscord_partner_sdk.so`

## 3. Connecting Discord Inside the App
1. Open the app → tap your **Profile icon** (top right) → **Account & scrobbling** → **Discord**.
2. Tap **Not connected** to launch the one-tap Discord OAuth2 Chrome Custom Tab (no password prompt inside the app).
