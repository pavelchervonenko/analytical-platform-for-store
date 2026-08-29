import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("java")
    id("checkstyle")
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    // id("org.sonarqube") version "7.2.3.7755"
    id("jacoco")
}

group = "com.storeanalytics"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("com.networknt:json-schema-validator:3.0.2")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("docs/schemas")) {
        into("contracts/llm")
    }
    from(rootProject.layout.projectDirectory.dir("docs/prompts")) {
        into("prompts/llm")
    }
}

springBoot {
    buildInfo()
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "768m"
    maxParallelForks = 1
    forkEvery = 50
    System.getenv("DOCKER_API_VERSION")?.let {
        systemProperty("api.version", it)
    }
}

val operatorScriptSecurityTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs URL, dotenv, output and artifact security tests for operator scripts."
    workingDir(rootDir)
    commandLine("bash", "scripts/tests/security-hardening-test.sh")
}

val gradleSupplyChainIntegrityTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies Gradle wrapper and dependency trust roots without network access."
    workingDir(rootDir)
    commandLine("python3", "scripts/tests/verify-gradle-supply-chain.py")
}

tasks.named("test") {
    dependsOn(operatorScriptSecurityTest)
}

tasks.register<JavaExec>("llmEvalShadow") {
    group = "verification"
    description = "Plans or executes the local candidate YandexGPT shadow matrix."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set(
        "com.storeanalytics.interpretation.generation.LlmEvalShadowRunner"
    )
    workingDir(rootDir)
}

tasks.register<JavaExec>("weeklyReviewAiShadow") {
    group = "verification"
    description = "Plans or executes the isolated v24/schema4 YandexGPT shadow corpus."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set(
        "com.storeanalytics.interpretation.review.ai.WeeklyReviewAiShadowRunner"
    )
    workingDir(rootDir)
}

val generatedOpenApi = layout.buildDirectory.file("openapi/current.json")

tasks.register<Test>("generateOpenApi") {
    group = "verification"
    description = "Generates the authenticated API OpenAPI artifact using an ephemeral test admin."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "com.storeanalytics.store.web.StoreDataStatusSecurityIntegrationTest"
                    + ".administratorOpenApiContainsStableFrontendSchemas"
        )
    }
    systemProperty("openapi.output", generatedOpenApi.get().asFile.absolutePath)
    outputs.file(generatedOpenApi)
}

val checkOpenApiCompatibility by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks the generated OpenAPI artifact for drift and breaking changes."
    dependsOn("generateOpenApi")
    workingDir(rootDir)
    commandLine(
        "node",
        "scripts/check-openapi-compatibility.mjs",
        "--baseline",
        "contracts/openapi/baselines/v10.json",
        "--committed",
        "contracts/openapi/current.json",
        "--generated",
        generatedOpenApi.get().asFile.absolutePath
    )
}

tasks.named("check") {
    dependsOn(checkOpenApiCompatibility, gradleSupplyChainIntegrityTest)
}

checkstyle {
    toolVersion = "13.3.0"
    configFile = file("${rootDir}/backend/config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

/*sonar {
    properties {
        property("sonar.projectKey", "pavelchervonenko_web-app-quiz")
        property("sonar.organization", "pavelchervonenko")
    }
}*/

jacoco {
    toolVersion = "0.8.14"
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

