import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RunGameTask
import stracciatella.modlist.ModListCreator
import stracciatella.modlist.ModListGenerator

plugins {
    alias(libs.plugins.stracciatella.fabric) apply false
    id("fabric-loom") version "1.14.0-alpha.50002"
    alias(libs.plugins.stracciatella) apply false
    alias(libs.plugins.stracciatella.base)
    `version-catalog`
    id("stracciatella-root")
}

repositories {
    val repos = this.toList()
    maven("https://reposilite.dasbabypixel.de/stracciatella") {
        name = "Stracciatella"
    }
    this.addAll(repos)
}

version = providers.gradleProperty("version").get()
group = providers.gradleProperty("group").get()

val modListLight = configurations.register("modlistLight")
val modList = configurations.register("modlist") { extendsFrom(modListLight.get()) }
val includeInCreator = configurations.detachedConfiguration(projects.loader.apply {
    targetConfiguration = "finalJar"
})

// Ingredients of the distributable mod jar: the loader's remapped Fabric mod and
// the remapped jar of every module.
val modJarLoader = configurations.detachedConfiguration(projects.loader.apply {
    targetConfiguration = "finalJar"
})
val modJarModules = configurations.detachedConfiguration(projects.modules.apply {
    targetConfiguration = "complete"
})

configurations.modLightRuntimeOnly.configure { extendsFrom(modListLight.get()) }
configurations.modFullRuntimeOnly.configure { extendsFrom(modList.get()) }
configurations.lightRuntimeClasspath.configure { extendsFrom(configurations.runtimeClasspath.get()) }
configurations.fullRuntimeClasspath.configure { extendsFrom(configurations.runtimeClasspath.get()) }

dependencies {
    modListLight(mods.fabric.api)
    modListLight(mods.sodium)
    modListLight(mods.reeses.sodium.options)
    modListLight(mods.modmenu)
//    modListLight(mods.viafabricplus) // doesnt work in dev idk
    modListLight(mods.`in`.game.account.switcher)

    lightRuntimeOnly(libs.fabric.loader)

    modList(mods.bundles.mods) { isTransitive = false }
    implementation(projects.loader) { targetConfiguration = "mergedJar" }
    stracciatellaModule(projects.modules)
}

tasks {
    register<ModListGenerator>("generateModList") {
        setup("generateModList.toml")
    }
    register<ModListCreator>("createModList") {
        modFiles.from(modList)
        modFiles.from(includeInCreator)
    }
    register<Jar>("stracciatellaModJar") {
        group = LifecycleBasePlugin.BUILD_GROUP
        description = "Builds the installable Fabric mod: the loader with every module bundled in"
        archiveBaseName = "stracciatella"
        archiveClassifier = "all"
        // zipTree only sees a plain file, so the configuration has to be depended on explicitly
        dependsOn(modJarLoader)
        // Loom writes Fabric-Mapping-Namespace and friends in there, so it has to survive the repackaging
        manifest.from(provider {
            zipTree(modJarLoader.singleFile).matching { include("META-INF/MANIFEST.MF") }.singleFile
        })
        from(provider { zipTree(modJarLoader.singleFile) }) {
            exclude("META-INF/MANIFEST.MF")
        }
        // where StracciatellaLanguageAdapter looks for modules inside the mod jar
        into("stracciatella/modules") {
            from(modJarModules)
        }
    }
}

allprojects {
    val isRootProject = this == rootProject
    tasks {
        withType<RunGameTask>().configureEach {
            maxHeapSize = "8G"
            workingDir(rootProject.projectDir)
            // Loom registers runClient/runServer/runClientRenderDoc in every
            // project that applies it, so an unqualified `gradlew runClient`
            // starts one per project — twelve at once. Only the root project's
            // run configurations are real: the module copies have no merged
            // loader jar, no classTweaker remapped into the named namespace and
            // no jol-core on the game classpath, so each one dies in loader init
            // behind its own Fabric error window. They can never do anything
            // useful, so they are disabled rather than merely documented.
            enabled = isRootProject
        }
    }
    pluginManager.apply {
        withPlugin("fabric-loom") {
            extensions.findByType<LoomGradleExtensionAPI>()?.apply {
                dependencies {
                    "minecraft"(rootProject.libs.minecraft)
                    "mappings"(officialMojangMappings())
                    "modImplementation"(rootProject.libs.fabric.loader)

                    // Fabric API. This is technically optional, but you probably want it anyway.
                    "modImplementation"(rootProject.libs.fabric.api)

                    "testImplementation"(rootProject.libs.junit.jupiter)
                    "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
                }
            }
        }
        withPlugin("checkstyle") {
            extensions.findByType<CheckstyleExtension>()?.apply {
                toolVersion = rootProject.libs.versions.checkstyle.get()
            }
        }
    }
}

gradle.taskGraph.whenReady {
    allTasks.filterIsInstance<JavaExec>().forEach {
        it.executable(it.javaLauncher.get().executablePath.asFile.absolutePath)
    }
}