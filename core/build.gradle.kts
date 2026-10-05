plugins {
    `java-library`
    kotlin("jvm")
}
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    // `-Dkoshchei.updateFixtures=true` regenerates the committed diagnosis-contract fixtures (requests and measurement)
    // and then fails on purpose (see ContractFixtures); rerun without it to verify.
    systemProperty("koshchei.updateFixtures", System.getProperty("koshchei.updateFixtures") ?: "false")
    systemProperty(
        "koshchei.contractFixturesDir",
        layout.projectDirectory.dir("src/test/resources/contract/diagnosis").asFile.absolutePath,
    )
}
// Pure core of the episode outer loop (design §4.1): no Temporal, no Spring, no JDBC — only Jackson's JSON tree.
// `api`: the public signatures (Snapshot, Candidate.toJson, DiagnosisRequest.toJson, ...) expose Jackson tree types.
dependencies {
    api(libs.jackson.databind)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
