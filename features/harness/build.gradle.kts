plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    // The port the harness implements is the turn's: the harness depends on the turn, never the other way
    // (docs/specs/harness.md, decision 11).
    api(project(":features-turns"))
    // The edges below are the harness's by the module law (gradle/module-law.txt): its session's folder and its
    // threads (sessions), the Context Getter's Claude Code session (launch, claude-code) and the MCP worker's hub
    // (mcp), for the items of this feature that build them.
    implementation(project(":features-sessions"))
    implementation(project(":features-launch"))
    implementation(project(":integrations-claude-code"))
    implementation(project(":integrations-http"))
    implementation(project(":integrations-upstream"))
    implementation(project(":integrations-mcp"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(project(":integrations-dialects-anthropic"))
    testImplementation(project(":integrations-dialects-openai-responses"))
    testImplementation(project(":integrations-dialects-openai-chat"))
    testImplementation(project(":integrations-providers-openai"))
    testImplementation(testFixtures(project(":features-turns")))
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesImplementation(libs.junit.platform.launcher)
    testFixturesApi(project(":core"))
    testFixturesApi(project(":features-turns"))
    testFixturesApi(project(":integrations-upstream"))
    testFixturesImplementation(testFixtures(project(":features-turns")))
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.ktor.client.core)
    testFixturesImplementation(libs.ktor.client.cio)
    testFixturesImplementation(project(":integrations-dialects-anthropic"))
}
