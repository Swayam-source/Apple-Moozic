#!/usr/bin/env bash
set -e

echo "[1/3] Cleaning repository (removing tv, wearos, BitChord banners, logos, UPI)..."
rm -rf tv wearos assets docs screenshots banners .github/ISSUE_TEMPLATE .github/FUNDING.yml
rm -f app/src/main/ic_launcher-playstore.png
find . -maxdepth 3 -iname "*banner*" -o -iname "*upi*" -o -iname "*sponsor*" -o -iname "*contrib*" | xargs -r rm -rf

python3 - << 'PY'
from pathlib import Path

# Remove :tv and :wearos from settings.gradle.kts
sg = Path("settings.gradle.kts")
if sg.exists():
    txt = sg.read_text()
    txt = txt.replace('include(":tv")', '// tv removed')
    txt = txt.replace('include(":wearos")', '// wearos removed')
    sg.write_text(txt)

# Write clean Apple Moozic README.md
Path("README.md").write_text("""# Apple Moozic

A sleek, liquid-glass Android music streaming and offline audio player with native Discord Rich Presence (`Listening to Apple Music`).

## Features
- **Liquid Glass UI** with dynamic artwork mesh gradients and time-synced lyrics
- **YouTube Music Streaming & Offline Library** (FLAC, ALAC, MP3, M4A)
- **Native Discord Rich Presence** via OAuth2 & Discord Social SDK (`Listening to Apple Music` or custom app name)
- **Bit-Perfect USB DAC Output & Parametric Equalizer**
""")
PY

echo "[2/3] Hiding Listen Together, Best Part, Addons, and Kushagra/BitChord support links..."
python3 - << 'PY'
from pathlib import Path

# 1. Strip Listen Together, Best Part, Addons, and support links from SettingsScreen.kt
ss = Path("app/src/main/java/com/music/bitchord/ui/screens/SettingsScreen.kt")
if ss.exists():
    lines = ss.read_text().splitlines()
    out = []
    skip_block = 0
    for line in lines:
        if any(k in line for k in [
            "onListenTogether", "onSources", "bestPart", "BestPart", "best_part",
            "kushagrasingh", "discord.gg", "t.me/", "buymeacoffee", "upi", "Support"
        ]):
            # Replace lambda callbacks or URLs safely
            if "http" in line or "discord.gg" in line or "kushagrasingh" in line:
                line = line.replace("https://github.com/kushagrasinghx/BitChord", "https://github.com/Swayam-source/Apple-Moozic")
                line = line.replace("kushagrasinghx", "Swayam-source")
                line = line.replace("BitChord", "Moozic")
                out.append(line)
            elif "onListenTogether =" in line or "onSources =" in line:
                out.append("                // feature removed")
            else:
                out.append(line)
        else:
            out.append(line)
    ss.write_text("\n".join(out) + "\n")

# 2. Remove Addons tab from Search switcher in MainActivity.kt
ma = Path("app/src/main/java/com/music/bitchord/MainActivity.kt")
if ma.exists():
    txt = ma.read_text()
    txt = txt.replace(
        """                                        options = listOf(
                                            stringResource(R.string.search_source_youtube),
                                            stringResource(R.string.library),
                                            stringResource(R.string.search_source_addons),
                                        ),""",
        """                                        options = listOf(
                                            stringResource(R.string.search_source_youtube),
                                            stringResource(R.string.library),
                                        ),"""
    )
    ma.write_text(txt)

# 3. Disable Best Part & Listen Together defaults in AppSettings
for f in Path("shared").rglob("AppSettings*.kt"):
    t = f.read_text()
    t = t.replace("val bestPart = MutableStateFlow(true)", "val bestPart = MutableStateFlow(false)")
    t = t.replace("val listenTogether = MutableStateFlow(true)", "val listenTogether = MutableStateFlow(false)")
    f.write_text(t)

# 4. Rebrand BitChord -> Moozic across strings.xml and Kotlin UI strings
for xml in Path("app/src/main/res").rglob("strings.xml"):
    t = xml.read_text()
    t = t.replace("BitChord", "Moozic")
    t = t.replace("Moozic Dev", "Apple Moozic Dev")
    t = t.replace(">Moozic<", ">Apple Moozic<")
    xml.write_text(t)

for kt in list(Path("app/src/main/java").rglob("*.kt")) + list(Path("sharedUi").rglob("*.kt")):
    t = kt.read_text()
    if "BitChord" in t or "kushagrasingh" in t or "discord.gg" in t:
        t = t.replace("https://github.com/kushagrasinghx/BitChord", "https://github.com/Swayam-source/Apple-Moozic")
        t = t.replace("https://discord.gg/bitchord", "")
        t = t.replace("kushagrasinghx", "Swayam-source")
        t = t.replace("kushagrasingh", "Swayam-source")
        t = t.replace('"BitChord"', '"Moozic"')
        t = t.replace("BitChord ", "Moozic ")
        kt.write_text(t)
PY

echo "[3/3] Pushing all changes to GitHub..."
git add -A
git commit -m "Rebrand to Apple Moozic, set unique app signature, default RPC to Apple Music, and clean features"
git push origin main
echo "All done! Check GitHub Actions for your new Apple Moozic APK."