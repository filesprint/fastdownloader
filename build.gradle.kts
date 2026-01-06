plugins {
    kotlin("jvm") version "2.2.20"
    id("org.graalvm.buildtools.native") version "0.9.28"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

kotlin {
    jvmToolchain(21) // Force Gradle to use JDK 21 (where you installed GraalVM)
}

application {
    mainClass.set("FastDownloaderKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "FastDownloaderKt"
    }
    // This includes dependencies (OkHttp) inside the jar so it's standalone
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

graalvmNative {
    binaries {
        named("main") {
            // Name of the output file (multi.exe)
            imageName.set("multi")

            // Standard options for better compatibility
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-all-security-services")
            buildArgs.add("--enable-url-protocols=https")
        }
    }
}
