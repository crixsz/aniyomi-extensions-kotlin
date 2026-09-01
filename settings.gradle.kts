/**
 * Add or remove modules to load as needed for local development here.
 */
val loadedExtensions = mutableListOf<Pair<String, String>>()

fun loadDevExtension(lang: String, name: String) {
    loadedExtensions += lang to name
    loadIndividualExtension(lang, name)
}

//loadAllIndividualExtensions()
loadIndividualExtension("en", "hentaistream")
loadIndividualExtension("en", "watchhentai")
//loadIndividualExtension("en", "hentaihavenxx")

/**
 * ===================================== COMMON CONFIGURATION ======================================
 */
include(":core")

// Load only modules needed by currently loaded extensions.
loadedExtensions
    .mapNotNull { (lang, name) -> findThemePkg(lang, name) }
    .distinct()
    .forEach { include("lib-multisrc:$it") }

/**
 * ======================================== HELPER FUNCTION ========================================
 */
fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            loadIndividualExtension(dir.name, subdir.name)
        }
    }
}
fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun findThemePkg(lang: String, name: String): String? {
    val buildFile = File(rootDir, "src/$lang/$name/build.gradle")
    if (!buildFile.exists()) return null

    val match = Regex("""themePkg\s*=\s*['\"]([^'\"]+)['\"]""").find(buildFile.readText())
    return match?.groupValues?.get(1)
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}
