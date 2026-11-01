plugins {
    kotlin("jvm")
    application
}

application {
    mainClass = "org.enderlink.cli.MainKt"
}

group = "org.enderlink"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}