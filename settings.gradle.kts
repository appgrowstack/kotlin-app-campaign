pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kotlin-app-campaign"

// Kotlin/JVM だけ。Android の SDK を要らない（アプリの :state と headless のテストからも使える）
include(":campaign-core")
