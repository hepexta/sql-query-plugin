import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA merged its Community and Ultimate artifacts in 2025.3, so the unified
        // intellijIdea(...) dependency is the correct one for build 253 and later.
        intellijIdea(providers.gradleProperty("platformVersion").get())
        // Bundled in both IDEA editions: gives us the com.intellij.database.util.DbUtil API
        // that exposes the PostgreSQL JDBC driver shipped with the IDE.
        bundledPlugin("com.intellij.database")

        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    // Used only by the headless integration check in tools/, never at IDE runtime.
    testImplementation("org.postgresql:postgresql:42.7.8")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
    buildSearchableOptions = false

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnit()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
}
