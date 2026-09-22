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
        // Use the unified IntelliJ IDEA dependency.
        //
        // Note the naming trap for 2026.2: intellijIdeaCommunity(...) looks for the
        // "idea:ideaIC" Maven coordinate, which JetBrains no longer publishes for this
        // version, so it fails with "Could not find idea:ideaIC:2026.2.3". The unified
        // intellijIdea(...) resolves and is the supported route. It yields the Ultimate
        // distribution, which is also what IntelliJ Platform Gradle Plugin 2.19.0 ships.
        // See the `test` task below for how the Ultimate-only startup logging is handled.
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
        // IntelliJ IDEA 2026.2 ships JBR 25 and its platform classes are Java 25 bytecode
        // (class file major version 69), so the plugin must be compiled with JDK 25.
        // Compiling against 2026.2 with JDK 21 fails with "class file has wrong version 69.0".
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("javaToolchain").get().toInt())
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
