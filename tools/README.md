# tools/

Binaries the patcher shells out to. **Not committed** (see `.gitignore`) — fetch them here.

| File                 | Purpose                                  | Source |
|----------------------|------------------------------------------|--------|
| `APKEditor.jar`      | Merge split APKs → one universal APK     | https://github.com/REAndroid/APKEditor/releases (V1.4.3 tested) |
| `baksmali.jar`       | dex → smali (decompile one dex)          | https://bitbucket.org/JesusFreke/smali/downloads (2.5.2 tested) |
| `smali.jar`          | smali → dex (reassemble)                 | same as above |
| `pancake-stable.dex` | Compiled STABLE layer, added as classesN | built from `../stable` (M2 stub currently a hand-written smali) |
| `liblsplant.so`      | ART hook backend (arm64-v8a)             | built from `../stable` NDK (M3) |

`zipalign` and `apksigner` come from the Android SDK build-tools (auto-located);
`keytool` from the JDK.

### Quick fetch (bash)
```bash
cd tools
curl -L -o APKEditor.jar https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar
curl -L -o baksmali.jar  https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
curl -L -o smali.jar     https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
```

### The M2 stub `pancake-stable.dex`
Until the Gradle/Kotlin build is wired, `pancake-stable.dex` is assembled from a minimal
hand-written smali `PancakeBootstrap.start(Application, Context)` that logs
`🥞 Pancakeify alive`. Rebuild it with:
```bash
java -jar smali.jar a stable_smali -o pancake-stable.dex
```
(where `stable_smali/com/pancakeify/stable/PancakeBootstrap.smali` is the stub).
