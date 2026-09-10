# 🥞 Pancakeify

**Spicetify for Android Spotify.** Custom themes and JS/CSS plugins injected into the
*original* Spotify app — the same way TikTok You / exteraGram mod their host apps
(patch the real APK, don't rewrite it).

Named after [Spicetify](https://spicetify.app/); architecture reverse-engineered from
the **windukk** (a.k.a. "Индюк") TikTok You mod, but rebuilt on open-source components.

> ⚠️ For personal, interoperability/customization use with your own legally obtained
> copy of Spotify. You must re-sign with your own key; the patched app is a separate,
> self-signed build and will not receive Play updates.

## How it works

Pancakeify mirrors the windukk two-layer design:

```
 ┌─────────────────────────── patched Spotify.apk ───────────────────────────┐
 │  SpotifyApplication.attachBaseContext(ctx)                                  │
 │        └─► PancakeBootstrap.start(app, ctx)   ← injected (1 smali call)     │
 │                                                                            │
 │  STABLE LAYER  (baked in, rarely changes)          assets/pancake/          │
 │   • PancakeBootstrap  – entry, guards, logging      ├─ pancake.dex  (mod)   │
 │   • DexLoader         – splice mod dex into the     ├─ manifest.json         │
 │                         host BaseDexClassLoader      └─ themes/…             │
 │   • HookEngine        – LSPlant (native ART hook)  lib/arm64-v8a/           │
 │        disableHiddenApiRestrictions + hooking       └─ liblsplant.so         │
 │        then reflectively calls ↓                                            │
 │                                                                            │
 │  DYNAMIC LAYER  (the mod itself, hot-updatable)                            │
 │   • com.pancakeify.mod.Main.start(app, ctx)                                 │
 │        └─ ThemeEngine  – hook WebView → inject CSS/JS (Spicy-Lyrics style)  │
 │        └─ PluginLoader – load user plugins from /sdcard/Pancakeify/         │
 └────────────────────────────────────────────────────────────────────────────┘
```

* **Stable vs dynamic split** = Spicetify core vs themes/extensions. The stable layer is
  a fixed API compiled into the APK; the dynamic layer (`pancake.dex`) carries all the
  features and can be replaced/updated without re-patching the whole app.
* **Hooking** uses [LSPlant](https://github.com/LSPosed/LSPlant) — actively maintained,
  tracks new ART. (windukk ships a proprietary ART hooker; the *old* LSPatch fork the
  user tried crashed on Android 17 because its hook layer lagged. LSPlant fixes that.)
* **Theming** leverages Spotify's heavy WebView usage (11/13 dex reference WebView):
  hook `WebView`/`WebViewClient` and inject CSS/JS.

## Repo layout

| Path        | What                                                                    |
|-------------|-------------------------------------------------------------------------|
| `patcher/`  | `pancakeify.py` CLI: merge splits → apktool decode → inject bootstrap call → add stable dex + assets + lsplant → build → zipalign → sign |
| `stable/`   | Android lib (Kotlin) → compiled to `pancake-stable.dex`. Bootstrap, DexLoader, HookEngine. |
| `mod/`      | Android lib (Kotlin) → compiled to `pancake.dex`. `Main` + ThemeEngine + PluginLoader. |
| `tools/`    | Prebuilt tooling (apktool, baksmali, lsplant .so). Not committed.        |
| `docs/`     | Design notes, RE findings.                                               |

## Target (current)

Spotify `com.spotify.music` **9.1.80.2221**, Application `com.spotify.music.SpotifyApplication`.
Device validated against: Pixel 10 Pro, Android 17 (SDK 37).

## Roadmap

- [x] RE windukk stable layer (bootstrap + dex loader + hook init)
- [x] Pull & analyze target Spotify APK, find injection point
- [ ] **M1** patcher: merge splits + apktool round-trip + re-sign (no mod yet) → installs & runs
- [ ] **M2** inject bootstrap call + stable dex that just logs "Pancakeify alive"
- [ ] **M3** integrate LSPlant, hook a trivial method to prove hooking on Android 17
- [ ] **M4** dynamic dex load (`Main.start`) + WebView CSS/JS injection → first theme
- [ ] **M5** plugin loader + theme format + sample "Pancake Lyrics" plugin
- [ ] **M6** OTA update channel for the dynamic layer (signed, SHA-256 manifest)

See `docs/ARCHITECTURE.md` for detail.
