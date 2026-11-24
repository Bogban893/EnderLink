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
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
