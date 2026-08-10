plugins {
    java
}

group = "fr.antho"
version = "1.8.0"

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
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21) // Bytecode compatible avec Java 21+, Paper 26.2 tourne sur Java 25.
}

tasks.jar {
    archiveBaseName.set("RealisticWorldGenerator")
}
