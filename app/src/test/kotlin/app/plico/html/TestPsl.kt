package app.plico.html

import java.io.File

/** The bundled list, read from the assets directory (Gradle runs unit tests in the module directory). */
internal val testPsl: Psl by lazy { Psl(File("src/main/assets/public_suffix_list.dat").readText()) }
