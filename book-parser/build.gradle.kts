plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":viewer-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    // XmlPullParser API only: provided by android.util.Xml on device, kxml2 jar
    // (which bundles the org.xmlpull.v1 interfaces) in JVM tests
    compileOnly("net.sf.kxml:kxml2:2.3.0")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser impl for JVM tests (Android ships its own)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Golden-fixture corpora can be large; allow overriding via -PfixtureDir
    systemProperty("fixture.dir", project.findProperty("fixtureDir") ?: "")
    maxHeapSize = "1g"
}
