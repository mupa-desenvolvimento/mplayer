pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mupa_player_enterprise"
include(":app")
include(":agent")
include(":mplayer_renner")
include(":core-player")
include(":core-network")
include(":core-storage")
include(":core-manifest")
include(":engage-camera")
include(":engage-vision")
include(":engage-analytics")
include(":engage-tts")
include(":engage-ui")
