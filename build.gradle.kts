import java.util.zip.ZipFile

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
            configurations.getByName(name).files.filter { it.extension == "jar" }.forEach { jar ->
                ZipFile(jar).use { zip ->
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
        }
    }
}
