package com.securevault.pro.presentation.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Material icon ban, enforced by the build instead of by review.
 *
 * The owner's instruction was absolute: no standard Material marks anywhere in this app. Every
 * screen drew them for a year, so the ban only holds if something checks it on every run — this is
 * that check. It reads the shipped sources, not a hand-maintained list, so a single re-added
 * `Icons.Rounded.Lock` fails the suite.
 */
class MaterialIconsAreBannedTest {

    private val bannedTokens = listOf(
        "androidx.compose.material.icons",
        "material-icons",
        // a qualified or bare reference to the Material icon object
        Regex("""(?<![\w.])Icons\.[A-Za-z]""")
    )

    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            val nested = File(dir, "app")
            if (File(nested, "src/main/java").isDirectory) return nested
            dir = dir.parentFile
            hops++
        }
        return File("")
    }

    private fun kotlinSources(root: File): List<File> {
        val out = mutableListOf<File>()
        fun walk(f: File) {
            val children = f.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) walk(child)
                else if (child.name.endsWith(".kt")) out += child
            }
        }
        walk(File(root, "src/main/java"))
        return out
    }

    /** Line content that is only a comment does not draw anything, so it is not a violation. */
    private fun offendingLines(file: File): List<String> =
        file.readLines().mapIndexedNotNull { index, line ->
            val code = line.trim().removePrefix("//").trim()
            val isComment = line.trim().startsWith("//") || line.trim().startsWith("*") ||
                line.trim().startsWith("/*")
            if (isComment || code.isEmpty()) return@mapIndexedNotNull null
            val hit = bannedTokens.any { token ->
                if (token is Regex) token.containsMatchIn(code) else code.contains(token.toString())
            }
            if (hit) "${file.name}:${index + 1}: ${line.trim()}" else null
        }

    @Test
    fun noSourceInTheAppReferencesMaterialIcons() {
        val module = moduleDir()
        assertTrue("could not locate the app module", module.isDirectory)
        val offenders = kotlinSources(module).flatMap { offendingLines(it) }
        assertTrue(
            "Material icons are banned; these lines still reach for them:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun theMaterialIconsDependencyIsNotDeclared() {
        val module = moduleDir()
        val gradle = File(module, "build.gradle.kts")
        assertTrue("app/build.gradle.kts is missing", gradle.isFile)
        assertTrue(
            "material-icons must stay off the dependency graph",
            !gradle.readText().contains("material-icons")
        )
        val catalog = generateSequence(module) { it.parentFile }
            .map { File(it, "gradle/libs.versions.toml") }
            .firstOrNull { it.isFile }
        if (catalog != null) {
            assertTrue(
                "the version catalog must not offer material-icons either",
                !catalog.readText().contains("material-icons")
            )
        }
    }

    /**
     * The ban is only real if the glyph family covers what the app actually draws. Every glyph the
     * screens name must exist, or a call site would have been deleted rather than converted.
     */
    @Test
    fun everyGlyphTheScreensDrawExists() {
        val module = moduleDir()
        val named = Regex("""Glyph\.([a-zA-Z0-9]+)""")
            .findAll(kotlinSources(module).joinToString("\n") { it.readText() })
            .map { it.groupValues[1] }
            .toSet()
        val known = Glyph.entries.map { it.name }.toSet()
        val missing = (named - known).sorted()
        assertTrue("call sites name glyphs the family does not have: $missing", missing.isEmpty())
        assertTrue("the app draws no glyph at all", named.isNotEmpty())
    }
}
