// :modes-api, the contract every mode implements and the catalog that finds and checks modes (modes/README.md).
// Framework-free: it speaks :core and nothing else, so a mode reaches no feature through it.
plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    // The contract's inputs and answers are JSON (a call's input, a route's body): core's serialization, carried on.
    api(project(":core"))
}
