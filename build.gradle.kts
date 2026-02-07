plugins {
    kotlin("multiplatform") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

group = "com.vigil"
version = "0.1.0"

kotlin {
    // expect/actual class の Beta 警告を抑制
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()
    mingwX64 {
        binaries.executable {
            entryPoint = "vigil.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core:3.1.1")
            implementation("io.ktor:ktor-client-websockets:3.1.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.1.1")
        }
        jvmTest.dependencies {
            implementation("io.ktor:ktor-client-mock:3.1.1")
        }
        named("mingwX64Main") {
            dependencies {
                implementation("io.ktor:ktor-client-winhttp:3.1.1")
            }
        }
    }
}
