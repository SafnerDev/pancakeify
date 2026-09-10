# tools/

Binaries the patcher shells out to. **Not committed** (see `.gitignore`) — fetch them here.

| File                 | Purpose                                  | Source |
|----------------------|------------------------------------------|--------|
| `APKEditor.jar`      | Merge split APKs → one universal APK     | https://github.com/REAndroid/APKEditor/releases (V1.4.3 tested) |
| `baksmali.jar`       | dex → smali (decompile one dex)          | https://bitbucket.org/JesusFreke/smali/downloads (2.5.2 tested) |
| `smali.jar`          | smali → dex (reassemble)                 | same as above |
| `pancake-stable.dex` | Compiled STABLE layer (+ bundled Pine), added as classesN | `bash ../stable/build_stable.sh` |
| `pine/`              | Extracted Pine AAR (classes.jar + jni/*/libpine.so) | fetched (below) |
| `libpine.so`         | Pine ART hook backend (arm64-v8a)        | copied from `pine/jni/arm64-v8a/` by build_stable.sh |

`zipalign` and `apksigner` come from the Android SDK build-tools (auto-located);
`keytool` from the JDK.

### Quick fetch (bash)
```bash
cd tools
curl -L -o APKEditor.jar https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar
curl -L -o baksmali.jar  https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
curl -L -o smali.jar     https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
```

### Fetch Pine (hook backend)
```bash
cd tools
curl -L -o pine-core.aar https://repo1.maven.org/maven2/top/canyie/pine/core/0.3.0/core-0.3.0.aar
mkdir -p pine && (cd pine && unzip -o ../pine-core.aar)   # -> pine/classes.jar, pine/jni/*/libpine.so
```
Then `bash ../stable/build_stable.sh` compiles the stable layer and bundles Pine into
`pancake-stable.dex`, and stages `libpine.so`.

### M2 stub (historical)
`tools/stable_smali/…/PancakeBootstrap.smali` is the original M2 hand-written stub that only
logs `🥞 Pancakeify alive`. Superseded by the Java build (`../stable/java`), kept for reference:
`java -jar smali.jar a stable_smali -o pancake-stable.dex`.
