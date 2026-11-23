plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // Main scope: Kotlin stdlib only (AGENTS.md P1). Real crypto is a test-only dependency.
    testImplementation(project(":crypto-jvm"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        // Golden vectors are rewritten only on purpose: gradlew :mesh-core:test -PwriteVectors
        if (!providers.gradleProperty("writeVectors").isPresent) excludeTags("write-vectors")
    }
}
