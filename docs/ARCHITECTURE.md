# Pancakeify architecture & RE notes

## Where this comes from

Reverse-engineered from **TikTok You** by *windukk* ("Индюк"), a mod built like
exteraGram/TikTokYou: it patches the *original* app rather than reimplementing it.
windukk is **not** LSPatch or Xposed — it's a custom in-APK patcher + ART hook runtime.
Pancakeify reproduces its architecture for Spotify, on open-source parts.

### windukk two-layer design (the model we copy)

**Stable layer** (baked into the patched APK, rarely changes):
- `com.windukk.stable.StableBootstrap.start(Application, Context)` — injected call in the
  host `Application.attachBaseContext`.
- Loader `a/e` (updater) → descriptor `a/d` {version, dexList, baseDir, flag, buildHash};
  `a/f` splices the mod dex into the host `BaseDexClassLoader.pathList.dexElements`.
- `com.windukk.hook.HookBridge` — Xposed-style ART hooking via direct ArtMethod editing
  (`ART_METHOD_SIZE/BIAS`, `addHiddenApiExemptions`, `deoptimizeMethod`).
- Reflectively calls the dynamic entry.

**Dynamic layer** (`assets/tty/bundled/classes.dex`, hot-updatable):
- `com.windukk.mod.Main.start(Application, Context)` + 755 obfuscated feature classes.
- `manifest.json` {dynamicVersion, stableApiVersion, targetDexSha256}, `public_key.der`
  for signed OTA updates; integrity via SHA-256.

## Pancakeify mapping

| windukk                         | Pancakeify                                   | Status |
|---------------------------------|----------------------------------------------|--------|
| `StableBootstrap.start`         | `com.pancakeify.stable.PancakeBootstrap.start` | M2 ✅ (smali stub) |
| `a/f` dexElements splice        | `DexLoader` (child DexClassLoader by default; splice available) | code ✅, wire M4 |
| `HookBridge` (proprietary ART)  | `HookEngine` → **LSPlant** (open source)     | M3 |
| `com.windukk.mod.Main.start`    | `com.pancakeify.mod.Main.start`              | code ✅, load M4 |
| feature classes                 | `ThemeEngine` (WebView CSS/JS) + `PluginLoader` | M4/M5 |
| `manifest.json` + SHA-256 + der | same, in `assets/pancake/`                    | M6 |

## Target: Spotify

- `com.spotify.music` **9.1.80.2221**; Application `com.spotify.music.SpotifyApplication`
  (classes2.dex, extends obfuscated `Lp/s0m;`), overrides `attachBaseContext`, `onCreate`.
- **Injection**: one `invoke-static {p0, p0}, PancakeBootstrap;->start(...)` before the
  final `return-void` of `attachBaseContext` (p0 = this = Application = Context). No extra
  registers needed.
- 13 base dexes; **WebView referenced in 11** → CSS/JS injection is the theming path
  (Spicy-Lyrics analog = a "Pancake Lyrics" plugin).
- Split install (base + arm64_v8a + en + xxhdpi); patcher merges with APKEditor first.
- Native: `liborbit-jni-spotify.so`, and `librootChecker.so` → expect integrity/root checks
  to handle (M3+; re-signed app + possible Play Integrity friction on login).

## Patcher = surgical, not apktool

We do **not** apktool the whole app (Spotify resources are huge; full round-trip is slow
and fragile). Instead:

1. **Merge** splits → universal APK (APKEditor).
2. **baksmali only** the one dex that defines the host Application.
3. Inject the single bootstrap `invoke-static` before `attachBaseContext`'s `return-void`.
4. **smali** that dex back.
5. **Repack the zip**: replace that dex, add `classesN.dex` (stable), `assets/pancake/*`,
   `assets/themes/*`, `lib/arm64-v8a/liblsplant.so` — every other entry byte-identical,
   compression types preserved (STORED `.so` for `extractNativeLibs=false`).
6. **zipalign -p 4** + **apksigner** (self-signed key).

Validated: produces a signed 76 MB `dist/pancakeify-spotify.apk` with 14 dexes, the
injection in classes2, and the stable `start()` present.

## Legal / scope

Personal interoperability & customization on your own legally obtained Spotify copy. The
patched app is self-signed and separate; it won't get Play updates and may hit Spotify's
own integrity checks. We use open-source hooking (LSPlant) rather than copying windukk's
proprietary engine.
