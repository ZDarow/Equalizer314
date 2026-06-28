plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.ben.manes) // applied at root level
}

tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>().configureEach {
    // Exclude alpha/beta/rc/unstable versions by default
    rejectVersionIf {
        val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { candidate.version.uppercase().contains(it) }
        val regex = "^[0-9,.v-]+(-r)?$".toRegex()
        val isStable = stableKeyword || regex.matches(candidate.version)
        !isStable
    }
}
