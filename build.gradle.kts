import java.util.zip.ZipFile
import java.io.DataInputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.graalvm.native)
    application
}

group = "blockchain"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.mosaic.runtime)
    implementation(libs.compose.runtime)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mosaic.testing)
}

application {
    mainClass = "app.MainKt"
}

graalvmNative {
    binaries.named("main") {
        imageName = "ConsoleBlockchain"
        buildArgs.add("--no-fallback")
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

tasks.register("verifyComposeClasspath") {
    description = "Checks that Compose runtime classes have a single provider on both classpaths."
    doLast {
        listOf("runtimeClasspath", "testRuntimeClasspath").forEach { name ->
            val providers = mutableMapOf<String, String>()
            val metadataVersions = mutableSetOf<String>()
            configurations.getByName(name).files.filter { it.extension == "jar" }.forEach { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith(".kotlin_module") }.forEach { entry ->
                        DataInputStream(zip.getInputStream(entry)).use { input ->
                            val count = input.readInt()
                            check(count in 2..8) { "Unknown Kotlin metadata in ${jar.name}" }
                            val major = input.readInt()
                            val minor = input.readInt()
                            check(major < 2 || (major == 2 && minor <= 2)) {
                                "${jar.name} metadata $major.$minor exceeds the IDE Kotlin 2.2 baseline"
                            }
                            metadataVersions += "$major.$minor"
                        }
                    }
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("androidx/compose/runtime/") && it.name.endsWith(".class") }
                        .forEach { entry ->
                            check(providers.put(entry.name, jar.name) == null) {
                                "$name: duplicate ${entry.name} in ${jar.name}"
                            }
                        }
                }
            }
            check(providers.isNotEmpty()) { "$name: Compose runtime missing" }
            logger.lifecycle("$name: ${providers.size} Compose classes, no duplicates")
            logger.lifecycle("$name: Kotlin metadata ${metadataVersions.sorted().joinToString()}, compatible with 2.2")
        }
    }
}
