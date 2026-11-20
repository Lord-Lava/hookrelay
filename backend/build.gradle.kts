buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

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
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")

// Security override (documented, reviewed 2026-11-20): the Spring Boot 4.1.2 BOM
// manages com.rabbitmq:amqp-client 5.31.0, affected by four High advisories:
// CVE-2026-63337 (fixed 5.33.0), CVE-2026-69219 and CVE-2026-69220 (fixed 5.33.1),
// CVE-2026-75516 (fixed 5.34.0). Spring Boot 4.1.2 is the latest 4.1.x patch, so no
// Boot patch manages a fixed version. Pinning the managed transitive dependency is
// therefore necessary and has a documented security reason (AGENTS.md 3.3/4.1).
// RabbitMQ client 5.x remains backward compatible within the 5.x line; 5.33.0's
// breaking changes (default hostname verification, stricter TLS defaults,
// JSON-RPC allowlist) are compatible with Spring Boot's Rabbit auto-configuration.
// Verified by the RabbitMQ container integration tests. Re-evaluate on any Boot
// 4.1.x patch; amqp-client 5.37.1 is a newer candidate for the next review.
implementation("com.rabbitmq:amqp-client:${libs.versions.rabbitmqAmqpClient.get()}")

// Apache HttpClient 5 for SSRF-safe outbound webhook delivery (Phase 3 Step 3).
// Spring Boot 4.1.2 BOM does not manage httpclient5; pinned explicitly.
// Apache HttpClient 5.6.x is actively maintained, widely used, and supported by
// Spring Framework 6.x/7.x through HttpComponentsClientHttpRequestFactory.
// Provides connection-level control needed for address pinning, TLS enforcement,
// redirect disabling, and bounded response-body consumption.
implementation("org.apache.httpcomponents.client5:httpclient5:${libs.versions.apacheHttpClient5.get()}")
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
    // Credentials improve NVD rate limits; they never determine whether the audit fails.
    // Missing/stale advisory data and scanner errors must not produce a passing audit.
    autoUpdate = true
    failOnError = true
    failBuildOnCVSS = 7.0F
    skipTestGroups = false
    scanBuildEnv = true
    formats = listOf("HTML", "JSON")
    nvd.apiKey.set(providers.environmentVariable("NVD_API_KEY").filter { it.isNotBlank() })
    // Narrow, documented suppressions only (AGENTS.md 4.5): build-environment and
    // false-positive findings are listed in gradle/dependency-check-suppressions.xml
    // with per-CVE justification, owner and review date. No wildcard rules.
    suppressionFile.set(rootProject.file("gradle/dependency-check-suppressions.xml").absolutePath)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    inputs.file(rootProject.file("../docs/openapi.yaml"))
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
