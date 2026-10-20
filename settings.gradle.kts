plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "EnderLink"
include("core")
include("agent")
include("cli")