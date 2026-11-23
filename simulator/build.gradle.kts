plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.llenoxinn.taptap.simulator.MainKt")
}

dependencies {
    implementation(project(":mesh-core"))
    implementation(project(":crypto-jvm"))
    runtimeOnly(libs.slf4j.nop)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
