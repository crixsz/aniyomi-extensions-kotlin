/**
 * Add or remove modules to load as needed for local development here.
 */
val devExtensions = listOf(
    "en" to "watchhentai",
    "en" to "hentaigasm",
    // "en" to "hentaihavenxx",
    // "en" to "hentaistream",
)

/**
 * CI loading rules:
 *  - CI_MODULES="en/foo,all/bar" loads exactly those extensions (used per build chunk).
 *  - CI=true without CI_MODULES loads every extension (used when resolving lib dependents).
 *  - Otherwise the local dev list above is used.
 */
val ciModules = System.getenv("CI_MODULES")
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }

val isCi = System.getenv("CI") == "true"

val extensionsToLoad: List<Pair<String, String>> = when {
    !ciModules.isNullOrEmpty() -> ciModules.map { entry ->
        val parts = entry.split("/")
        require(parts.size == 2) { "CI_MODULES entry must look like lang/name: $entry" }
        parts[0] to parts[1]
    }

    isCi -> allExtensions()
    else -> devExtensions
}

/**
 * ===================================== COMMON CONFIGURATION ======================================
 */
include(":core")

// Extensions reference shared code as `project(":lib:<name>")`.
File(rootDir, "lib").eachDir {
    include(":lib:${it.name}")
}

// Extensions with a `themePkg` reference `project(":lib-multisrc:<themePkg>")`.
extensionsToLoad
    .mapNotNull { (lang, name) -> findThemePkg(lang, name) }
    .distinct()
    .forEach { include(":lib-multisrc:$it") }

extensionsToLoad.forEach { (lang, name) ->
    loadIndividualExtension(lang, name)
}

/**
 * ======================================== HELPER FUNCTION ========================================
 */
fun allExtensions(): List<Pair<String, String>> = buildList {
    File(rootDir, "src").eachDir { lang ->
        lang.eachDir { name ->
            add(lang.name to name.name)
        }
    }
}

fun loadAllIndividualExtensions() {
    allExtensions().forEach { (lang, name) -> loadIndividualExtension(lang, name) }
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
