import java.security.MessageDigest

plugins {
    kotlin("jvm")
    application
}

application {
    mainClass = "org.enderlink.desktop.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

group = "org.enderlink"
version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(project(":agent"))
    implementation("com.formdev:flatlaf:3.8")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

val buildInfoDirectory = layout.buildDirectory.dir("generated/build-info")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    inputs.files(
        rootProject.file("build.gradle.kts"),
        file("build.gradle.kts"),
        fileTree("src/main"),
        project(":core").file("build.gradle.kts"),
        project(":core").fileTree("src/main"),
        project(":agent").file("build.gradle.kts"),
        project(":agent").fileTree("src/main"),
    ).withPropertyName("applicationSources").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(buildInfoDirectory)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        inputs.files.files.filter { it.isFile }.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.forEach { source ->
            digest.update(source.relativeTo(rootDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            source.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count != -1) {
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
        val output = buildInfoDirectory.get().file("enderlink-build.properties").asFile
        output.parentFile.mkdirs()
        output.writeText("version=${project.version}\nid=$id\n", Charsets.UTF_8)
    }
}

sourceSets.main { resources.srcDir(buildInfoDirectory) }
tasks.processResources { dependsOn(generateBuildInfo) }
