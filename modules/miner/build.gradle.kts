plugins {
    id(libs.plugins.stracciatella.asProvider().get().pluginId)
}

stracciatella {
    main = "net.stracciatella.miner.MinerModule"
    id = "miner"
    name = "Miner"
    group = "net.stracciatella"
}

dependencies {
    compileOnly(projects.loader)
    compileOnly(project(":modules:bot"))
    compileOnly(project(":modules:gui"))
    compileOnly(project(":modules:testing"))
}
