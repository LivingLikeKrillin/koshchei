plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
    implementation(libs.temporal.sdk)
    implementation(libs.postgresql)
    implementation(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
application { mainClass.set("koshchei.host.WorkerKt") }

// The Gradle daemon keeps the environment it started with, so forward every KOSHCHEI_* from the live shell into
// the forked JVM. The working directory is host/, so a relative default policy path would miss the repo's policy/:
// pass the absolute path when the variable is unset.
fun JavaExec.forwardKoshcheiEnv() {
    System.getenv().filterKeys { it.startsWith("KOSHCHEI_") }.forEach { (k, v) -> environment(k, v) }
    if (System.getenv("KOSHCHEI_EPISODE_POLICY") == null) {
        environment("KOSHCHEI_EPISODE_POLICY", rootProject.file("policy/active.yaml").absolutePath)
    }
}
tasks.named<JavaExec>("run") { forwardKoshcheiEnv() }
tasks.register<JavaExec>("watcher") {
    group = "application"
    mainClass.set("koshchei.host.WatcherKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardKoshcheiEnv()
}
tasks.register<JavaExec>("cli") {
    group = "application"
    mainClass.set("koshchei.host.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardKoshcheiEnv()
}
