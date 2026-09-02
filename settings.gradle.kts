pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "outcomeprobe"

include("server")
include("cli")
include("examples:reference-consumer")
