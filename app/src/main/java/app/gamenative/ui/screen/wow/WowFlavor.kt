package app.gamenative.ui.screen.wow

import android.content.Context
import java.io.File

/**
 * The WoW products this launcher can boot. Each one lives in its own folder under the
 * shared install root (next to `Data/` and `.build.info`), exactly like a Battle.net install.
 */
enum class WowFlavor(
    val label: String,
    val subtitle: String,
    val product: String,
    val dir: String,
    private val exeNames: List<String>,
    val portal: String,
    val defaultFolder: String,
) {
    FOREVER(
        label = "Forever",
        subtitle = "FOREVER BETA (ARM64 NATIVE)",
        product = "wow_classic_beta",
        dir = "_classic_beta_",
        exeNames = listOf("WowB-ARM64.exe"),
        portal = "test",
        defaultFolder = "WoW Forever",
    ),
    RETAIL(
        label = "Retail",
        subtitle = "RETAIL (ARM64 NATIVE)",
        product = "wow",
        dir = "_retail_",
        exeNames = listOf("Wow-ARM64.exe", "WowArm64.exe", "Wow-arm64.exe"),
        portal = "us",
        defaultFolder = "World of Warcraft",
    ),
    ;

    val exeName get() = exeNames.first()

    /**
     * The ARM64 client in this flavor's folder. Blizzard's exe names differ per product, so after
     * the known names this falls back to any ARM64 .exe the CDN download put there.
     */
    fun exeFile(root: File): File {
        val flavorDir = File(root, dir)
        exeNames.map { File(flavorDir, it) }.firstOrNull { it.isFile }?.let { return it }
        flavorDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) && it.name.contains("arm64", ignoreCase = true) }
            ?.firstOrNull { exe -> listOf("launcher", "crash", "error").none { exe.name.contains(it, ignoreCase = true) } }
            ?.let { return it }
        return File(flavorDir, exeName)
    }

    companion object {
        private const val PREFS = "wow_forever"
        private const val KEY_FLAVOR = "flavor"

        /** The flavor the launcher is currently set up for. Read by the downloader and launch code. */
        @Volatile
        var current: WowFlavor = FOREVER
            private set

        private var loaded = false

        fun load(context: Context): WowFlavor {
            if (!loaded) {
                val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FLAVOR, null)
                current = entries.firstOrNull { it.name == saved } ?: FOREVER
                loaded = true
            }
            return current
        }

        fun select(context: Context, flavor: WowFlavor) {
            current = flavor
            loaded = true
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FLAVOR, flavor.name).apply()
        }
    }
}
