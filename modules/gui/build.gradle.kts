plugins {
    id(libs.plugins.stracciatella.asProvider().get().pluginId)
}

stracciatella {
    main = "net.stracciatella.gui.GuiModule"
    id = "gui"
    name = "Gui"
    group = "net.stracciatella"
}

dependencies {
    compileOnly(projects.loader)
    compileOnly(project(":modules:testing"))
}
