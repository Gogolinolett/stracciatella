plugins {
    id(libs.plugins.stracciatella.asProvider().get().pluginId)
}

tasks.checkstyleMain {
    this.maxWarnings = 100
}

stracciatella {
    main = "net.stracciatella.bot.BotModule"
    id = "bot"
    name = "Bot"
    group = "net.stracciatella"
    accessWidener("bot.accesswidener")
}

dependencies {
    compileOnly(projects.loader)
    compileOnly(project(":modules:camera"))
    compileOnly(project(":modules:pathfinding"))
    compileOnly(project(":modules:testing"))
}
