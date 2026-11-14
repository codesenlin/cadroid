plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app")

include(":pews-api")
project(":pews-api").projectDir = file("PEWS-API")
