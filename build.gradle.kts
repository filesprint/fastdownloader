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

graalvmNative {
    binaries {
        named("main") {
            imageName.set("multi")

            buildArgs.addAll(
                listOf(
                    "--no-fallback",
                    "--enable-http",
                    "--enable-https",
                    "-H:+StripDebugInfo",
                    "-H:NativeLinkerOption=-s"
                )
            )
        }
    }
}
