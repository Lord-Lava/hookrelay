plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
    alias(libs.plugins.owasp.dependency.check)
    alias(libs.plugins.cyclonedx)
}

group = "com.hookrelay"
version = "0.1.0-SNAPSHOT"
description = "Multi-tenant webhook delivery infrastructure"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Use Spring Boot's tested dependency set; do not hand-pin managed libraries.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("io.micrometer:micrometer-registry-prometheus")

    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Reproducible dependency selection. Commit generated lock state after a trusted first resolution.
dependencyLocking {
    lockAllConfigurations()
}

spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get())
    }
}

configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    // High/Critical findings fail the audit; suppressions require a reviewed, expiring exception.
    failBuildOnCVSS = 7.0F
    // The key is supplied by the CI secret store/environment, never committed to the repository.
    providers.environmentVariable("NVD_API_KEY").orNull?.let { apiKey ->
        nvd.apiKey.set(apiKey)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

val verifyImportHygiene = tasks.register("verifyImportHygiene") {
    group = "verification"
    description = "Reject retired Java EE and Jackson 2 core/databind imports."

    val javaSources = fileTree("src") {
        include("**/*.java")
    }
    inputs.files(javaSources)

    doLast {
        val forbiddenImports = listOf(
            Regex("""^\s*import\s+com\.fasterxml\.jackson\.(core|databind)\."""),
            Regex("""^\s*import\s+javax\.(servlet|validation|persistence|transaction|annotation)\.""")
        )
        val violations = javaSources.files.sortedBy { it.path }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbiddenImports.any { it.containsMatchIn(line) }) {
                    "${file.relativeTo(projectDir)}:${index + 1}: $line"
                } else {
                    null
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Forbidden legacy imports detected. Use Jackson 3 tools.jackson APIs and Jakarta EE namespaces:\n" +
                    violations.joinToString("\n")
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifyImportHygiene)
}

tasks.register("securityAudit") {
    group = "verification"
    description = "Runs dependency vulnerability analysis and generates a CycloneDX SBOM."
    dependsOn("dependencyCheckAnalyze", "cyclonedxBom")
}
