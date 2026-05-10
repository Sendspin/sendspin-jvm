plugins {
    kotlin("jvm")
    alias(libs.plugins.ksp)
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.okhttp)
    api(libs.java.websocket)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)
    implementation(libs.coroutines.core)
    implementation(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.github.OnFreund"
            artifactId = "sendspin-jvm"
        }
    }
}
