package com.pancakeify.stable

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.security.MessageDigest

/**
 * Extracts and loads the dynamic mod dex.
 *
 * windukk splices the mod dex straight into the host `BaseDexClassLoader.pathList.dexElements`
 * (see [spliceIntoHostLoader]). We default to the simpler, safer route: a child
 * [DexClassLoader] whose parent is the host loader. The mod can still see every host class
 * (parent-first delegation) and LSPlant resolves host `Method`s independently, so hooking
 * works either way. Use [spliceIntoHostLoader] only if a host code path must resolve mod
 * classes by name through its own loader.
 */
object DexLoader {
    private const val ASSET_DIR = "pancake"
    private const val DEX_ASSET = "pancake/pancake.dex"
    private const val MANIFEST_ASSET = "pancake/manifest.json"

    fun loadDynamicDex(ctx: Context, hostLoader: ClassLoader): ClassLoader {
        val cacheDir = File(ctx.codeCacheDir, "pancakeify").apply { mkdirs() }
        val dexFile = File(cacheDir, "pancake.dex")

        val bytes = ctx.assets.open(DEX_ASSET).use { it.readBytes() }
        verifyIntegrity(ctx, bytes)

        // Re-extract only if changed (cheap SHA compare) so warm starts are fast.
        if (!dexFile.exists() || sha256(dexFile.readBytes()) != sha256(bytes)) {
            dexFile.writeBytes(bytes)
        }

        val optDir = File(cacheDir, "oat").apply { mkdirs() }
        val loader = DexClassLoader(dexFile.absolutePath, optDir.absolutePath, null, hostLoader)
        Log.i(PancakeBootstrap.TAG, "Loaded dynamic dex (${bytes.size} bytes)")
        return loader
    }

    /** Compares the bundled dex against the SHA-256 recorded in the manifest, if present. */
    private fun verifyIntegrity(ctx: Context, dexBytes: ByteArray) {
        val manifest = runCatching {
            ctx.assets.open(MANIFEST_ASSET).use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return
        val want = Regex("\"targetDexSha256\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
            .find(manifest)?.groupValues?.get(1)?.lowercase() ?: return
        val got = sha256(dexBytes)
        check(got == want) { "pancake.dex integrity check failed: $got != $want" }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * Optional: windukk-style splice of a dex into the host classloader's dexElements array
     * (reflection on BaseDexClassLoader.pathList.dexElements + array concat). Left here as a
     * documented fallback; not used by default.
     */
    fun spliceIntoHostLoader(hostLoader: ClassLoader, extraDex: File, optDir: File) {
        val child = DexClassLoader(extraDex.absolutePath, optDir.absolutePath, null, hostLoader)
        val bdcl = Class.forName("dalvik.system.BaseDexClassLoader")
        val pathListF = bdcl.getDeclaredField("pathList").apply { isAccessible = true }
        val dexElementsF = Class.forName("dalvik.system.DexPathList")
            .getDeclaredField("dexElements").apply { isAccessible = true }

        val hostPathList = pathListF.get(hostLoader)
        val childPathList = pathListF.get(child)
        val hostElems = dexElementsF.get(hostPathList) as Array<*>
        val childElems = dexElementsF.get(childPathList) as Array<*>

        val merged = java.lang.reflect.Array.newInstance(
            hostElems.javaClass.componentType, childElems.size + hostElems.size
        )
        System.arraycopy(childElems, 0, merged, 0, childElems.size)
        System.arraycopy(hostElems, 0, merged, childElems.size, hostElems.size)
        dexElementsF.set(hostPathList, merged)
    }
}
