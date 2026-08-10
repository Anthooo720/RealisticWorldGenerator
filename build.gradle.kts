import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "fr.antho"
version = "1.9.1"

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.111-stable")
}

java {
    // Paper 26.2 est compilé pour JVM 25 ; le compilateur doit donc être Java 25.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// Le plugin reste émis en bytecode Java 21, mais Gradle doit accepter le classpath
// Paper 26.2 (JVM 25) pendant la compilation.
configurations.configureEach {
    if (isCanBeResolved) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set("RealisticWorldGenerator")
}
