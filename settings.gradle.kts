pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central (repo.maven.apache.org is
        // rate-limited from some networks)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "airplay-tv"
include(":app")
