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

// JetBrains' forwarding jar and AndroidX's real runtime share a filename in distributions.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.compose.runtime:runtime-desktop"))
            .using(module("androidx.compose.runtime:runtime-desktop:${libs.versions.compose.get()}"))
            .because("Use the AndroidX runtime directly instead of its empty forwarding jar")
    }
}

application {
    mainClass = "MainKt"
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

val buildInfoDirectory = layout.buildDirectory.dir("generated/build-info")
val generateBuildInfo by tasks.registering {
    val info = mapOf(
        "application" to project.version.toString(), "gradle" to gradle.gradleVersion,
        "java_toolchain" to "21", "kotlin" to libs.versions.kotlin.get(),
        "serialization" to libs.versions.serialization.get(), "coroutines" to libs.versions.coroutines.get(),
        "mosaic" to libs.versions.mosaic.get(), "compose" to libs.versions.compose.get(),
        "native_build_tools" to libs.versions.native.get(), "junit" to libs.versions.junit.get(),
    )
    inputs.properties(info)
    outputs.dir(buildInfoDirectory)
    doLast {
        buildInfoDirectory.get().file("build-info.properties").asFile.apply {
            parentFile.mkdirs()
            writeText(info.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        }
    }
}
sourceSets.main { resources.srcDir(buildInfoDirectory) }
tasks.processResources { dependsOn(generateBuildInfo) }

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
