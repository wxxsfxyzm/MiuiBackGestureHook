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
        // miuix SNAPSHOT builds are published to GitHub Packages, which requires a token.
        maven("https://maven.pkg.github.com/compose-miuix-ui/miuix") {
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content { includeGroup("top.yukonga.miuix.kmp") }
        }
    }
}

rootProject.name = "MiuiBackGestureHook"
include(":app", ":hidden-api")

// Use the matching local checkout when validating unreleased miuix components.
providers.gradleProperty("miuixSourceDir").orNull?.let { sourceDir ->
    includeBuild(sourceDir) {
        dependencySubstitution {
            listOf(
                "core", "ui", "shader", "squircle", "blur", "glass", "glass-icons",
                "preference", "icons",
            ).forEach { component ->
                substitute(module("top.yukonga.miuix.kmp:miuix-$component-android"))
                    .using(project(":miuix-$component"))
            }
        }
    }
}
