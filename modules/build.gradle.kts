// Named module jars, for the dev runs
configurations.register("default") {
    isCanBeResolved = false
    isCanBeConsumed = true
}

// Remapped module jars, for the distributable mod jar
configurations.register("complete") {
    isCanBeResolved = false
    isCanBeConsumed = true
}

fun module(path: String) {
    dependencies {
        "default"(project(path, "stracciatellaNamed"))
        "complete"(project(path, "stracciatellaComplete"))
    }
}
// declare all modules here
module("core")
module("camera")
module("fullscreen")
module("anonymous-modlist")
module("pathfinding")
module("testing")
module("bot")
module("miner")
module("gui")
