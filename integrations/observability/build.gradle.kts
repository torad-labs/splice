plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.sdk.trace)
    implementation(libs.opentelemetry.sdk.metrics)
    implementation(libs.opentelemetry.sdk.logs)
    // The OTLP artifact includes HTTP/protobuf and gRPC exporters for all three signals.
    implementation(libs.opentelemetry.exporter.otlp)

    testImplementation(libs.kotlinx.coroutines.test)
}
