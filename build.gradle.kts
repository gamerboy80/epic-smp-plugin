import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.plugins.jvm.JvmTestSuite
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    checkstyle
    id("com.github.spotbugs") version "6.5.12"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
    java
}

group = "com.epicsmp"

fun getTime(): String {
    val sdf = SimpleDateFormat("yyMMdd-HHmm")
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}

version = (if (!hasProperty("ver")) {
    "${getTime()}-SNAPSHOT"
} else {
    val ver = property("ver") as String
    val base = if (ver.startsWith("v")) ver.drop(1) else ver.replace('/', '-')
    if (ver.startsWith("v") && !ver.lowercase().contains("-rc-")) base else "$base-SNAPSHOT"
}).uppercase()

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
        content {
            includeModule("io.papermc.paper", "paper-api")
            includeModule("net.md-5", "bungeecord-chat")
            includeGroup("io.papermc.adventure")
        }
    }

    maven {
        name = "minecraft"
        url = uri("https://libraries.minecraft.net")
        content {
            includeModule("com.mojang", "brigadier")
        }
    }

    mavenCentral()
    maven {
        name = "placeholderapi"
        url = uri("https://repo.helpch.at/releases/")
        content { includeGroup("me.clip") }
    }
    maven {
        name = "codemc"
        url = uri("https://repo.codemc.io/repository/maven-releases/")
        content { includeGroup("com.github.retrooper") }
    }
    ivy {
        name = "packetEventsServerDistribution"
        url = uri("https://github.com/retrooper/packetevents/releases/download/")
        patternLayout { artifact("v[revision]/packetevents-spigot-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeModule("com.github.retrooper", "packetevents-server-test") }
    }
}

val mockitoAgent = configurations.create("mockitoAgent")
val packetEventsDistribution = configurations.create("packetEventsDistribution")

// The official server plugin bundles this legacy interface as a classloader fallback.
// Include the same class in tests running Paper's Adventure 5, without shadowing its API.
val packetEventsTestCompatibility = tasks.register<Zip>("packetEventsTestCompatibility") {
    from({ zipTree(packetEventsDistribution.singleFile) }) {
        include("net/kyori/adventure/util/Buildable\$Builder.class")
    }
    archiveFileName.set("packetevents-test-compatibility.jar")
    destinationDirectory.set(layout.buildDirectory.dir("test-runtime"))
}
val packetEventsCompatibilityFiles = files(packetEventsTestCompatibility.flatMap { it.archiveFile })
    .builtBy(packetEventsTestCompatibility)

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.132-stable")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    compileOnly("me.clip:placeholderapi:2.12.3") { isTransitive = false }
    testImplementation("com.github.retrooper:packetevents-spigot:2.14.0")
    packetEventsDistribution("com.github.retrooper:packetevents-server-test:2.14.0@jar")
    testRuntimeOnly(packetEventsCompatibilityFiles)

    // Code quality and unit testing. Not required for code functionality.
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.4")
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:4.10.4")
    testImplementation("io.papermc.paper:paper-api:26.2.build.132-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")

    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.117.0")
    testImplementation("org.mockito:mockito-core:5.24.0")
    mockitoAgent("org.mockito:mockito-core:5.24.0") { isTransitive = false }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}

tasks.runServer {
    minecraftVersion("26.2")
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter("6.1.3")

            dependencies {
                implementation(sourceSets.main.get().output)
                implementation(sourceSets.test.get().output)
                implementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.117.0")
                implementation("org.mockito:mockito-core:5.24.0")
                implementation("io.papermc.paper:paper-api:26.2.build.132-stable")
                implementation("com.github.retrooper:packetevents-spigot:2.14.0")
                runtimeOnly(packetEventsCompatibilityFiles)
            }

            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(tasks.test)
                        jvmArgs("--enable-native-access=ALL-UNNAMED", "-javaagent:${mockitoAgent.asPath}")
                    }
                }
            }
        }
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.implementation.get())
}

configurations.named("integrationTestCompileOnly") {
    extendsFrom(configurations.testCompileOnly.get())
}

tasks.processResources {
    inputs.properties(mapOf("pluginName" to rootProject.name,
        "pluginVersion" to project.version.toString(), "pluginGroup" to project.group.toString()))
    filesMatching("**/plugin.yml") {
        expand(mapOf("NAME" to rootProject.name, "VERSION" to version, "PACKAGE" to project.group))
    }
}

checkstyle {
    toolVersion = "14.0.0"
    maxWarnings = 0
}

configurations.named("checkstyle") {
    resolutionStrategy.capabilitiesResolution
        .withCapability("com.google.collections:google-collections") {
            select("com.google.guava:guava:23.0")
        }
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required.set(false)
        html.required.set(true)
    }
}

tasks.withType<SpotBugsTask>().configureEach {
    reports.create("html") {
        required.set(true)
    }
    reports.create("xml") {
        required.set(false)
    }
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(shadowJar)
}

tasks.register("printProjectName") {
    doLast {
        println(rootProject.name)
    }
}

tasks.register("release") {
    dependsOn("build")

    doLast {
        if (!version.toString().endsWith("-SNAPSHOT")) {
            val releaseJar = layout.buildDirectory.file("libs/${rootProject.name}.jar").get().asFile
            Files.move(shadowJar.get().archiveFile.get().asFile.toPath(),
                releaseJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
