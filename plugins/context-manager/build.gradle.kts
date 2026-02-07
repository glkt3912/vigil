plugins {
    kotlin("multiplatform") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

group = "com.vigil.plugins"
version = "0.1.0"

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
