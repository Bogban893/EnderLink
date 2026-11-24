plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "EnderLink"
include("core")
include("agent")
include("coordinator")
include("cli")
include("desktop")
include("connectivity-test")
include("iroh-test")
