plugins {
    java
    application
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "su.grinev"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "su.grinev.mediabot.MediaBotApplication"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")

    // Serves the finished file over a link instead of uploading it. The whole reason a 2 GB
    // download can be handed over at all: nothing goes through the Bot API's upload path.
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Compile-time annotation processor; nothing of it belongs on the runtime classpath.
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Pinned explicitly: Spring Boot 4 defaults to Jackson 3 (tools.jackson), and the agent
    // core ported from photo-agent is written against the Jackson 2 API.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    // Telegram Bot API. The version insta-dl already runs in production; DefaultBotOptions
    // carries the base-url override that points the client at a local Bot API server.
    implementation("org.telegram:telegrambots:6.9.7.1")

    // The job queue. A download is minutes long, so a job has to survive a restart being
    // visible rather than silently lost — which an in-memory queue cannot do.
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    // Console output is UTF-8; without this the Windows OEM codepage mangles it.
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
        // sqlite-jdbc loads a native library; without this Java 25 prints a warning block on every
        // start that looks like a failure and is not.
        "--enable-native-access=ALL-UNNAMED")
}

// The same application with the chat replaced by stdin. Everything else — queue, workers, tools,
// model — is the deployed thing.
tasks.register<JavaExec>("console") {
    group = "application"
    description = "Talk to the agent from the terminal instead of Telegram."
    mainClass = application.mainClass
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED")
    // A system property rather than a program argument, so --args stays free for the caller:
    //   ./gradlew console --args='--mediabot.llm.preflight=false'
    systemProperty("spring.profiles.active", "console")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Tests that need a real yt-dlp and network read this; unset means they skip themselves.
    systemProperty("mediabot.live", System.getProperty("mediabot.live") ?: "")
    // Optional cookies for hosts that refuse anonymous requests, YouTube above all.
    systemProperty("mediabot.cookies", System.getProperty("mediabot.cookies") ?: "")
    // And the JS runtime YouTube's "n challenge" needs. Forwarded explicitly, like the two above:
    // -D reaches the Gradle JVM, not the one the tests run in, and a property that quietly fails
    // to arrive here looks exactly like the feature not working.
    systemProperty("mediabot.js", System.getProperty("mediabot.js") ?: "")
    testLogging {
        // Only for the live run: those tests report what a real host actually answered, and a
        // measurement nobody sees is not one. An ordinary build stays quiet.
        showStandardStreams = System.getProperty("mediabot.live") != null
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
