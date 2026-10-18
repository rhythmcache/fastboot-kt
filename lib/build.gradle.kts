plugins {
    kotlin("jvm")
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed", "standardOut", "standardError")
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            groupId = "com.github.rhythmcache"
            artifactId = "fastboot-kt"
            version = "1.0.3"

            pom {
                name.set("fastboot-kt")
                description.set("Pure Kotlin, fastboot protocol library")
                url.set("https://github.com/rhythmcache/fastboot-kt")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("rhythmcache")
                        name.set("rhythmcache")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/rhythmcache/fastboot-kt.git")
                    developerConnection.set("scm:git:ssh://github.com/rhythmcache/fastboot-kt.git")
                    url.set("https://github.com/rhythmcache/fastboot-kt")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
