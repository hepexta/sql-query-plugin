rootProject.name = "sql-query-plugin"

plugins {
    // Resolves the JDK 21 toolchain if it is not already installed locally.
    // Must stay >= 1.0.0: 0.9.0 and earlier reference JvmVendorSpec.IBM_SEMERU, which Gradle 9
    // removed, so they fail with NoSuchFieldError as soon as the resolver has to map a vendor
    // (e.g. while the IDE syncs the project). 1.0.0 also requires Java 17+ to run.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
