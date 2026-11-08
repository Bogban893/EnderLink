plugins {
    kotlin("jvm")
    application
}

application {
    mainClass = "org.enderlink.MainKt"
}

val nativeOs = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows"
    System.getProperty("os.name").startsWith("Mac") -> "macos"
    System.getProperty("os.name").startsWith("Linux") -> "linux"
    else -> error("Unsupported OS for WebRTC")
}
val nativeArch = when (System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    "arm", "arm32" -> "aarch32"
    else -> error("Unsupported architecture for WebRTC")
}

group = "org.enderlink"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.onvoid.webrtc:webrtc-java:0.20.0")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.20.0:$nativeOs-$nativeArch")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
