plugins {
    kotlin("jvm")
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application {
    mainClass = "org.enderlink.irohtest.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test { useJUnitPlatform() }

tasks.named<JavaExec>("run") { standardInput = System.`in` }
