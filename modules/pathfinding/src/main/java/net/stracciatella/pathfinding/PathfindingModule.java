package net.stracciatella.pathfinding;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
// FAKT: Hier liegen jetzt die Standard-Instanzen (lines, solid, etc.)

// FAKT: Das ist die Klasse für das Objekt selbst
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.stracciatella.module.Module;
import net.stracciatella.pathfinding.commands.NavigateCommands;
import net.stracciatella.pathfinding.commands.PathCommands;
import net.stracciatella.pathfinding.display.PathDisplay;
import net.stracciatella.pathfinding.logic.MeshManager;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.test.EnderPearlTests;
import net.stracciatella.pathfinding.test.JourneyTests;
import net.stracciatella.pathfinding.test.MeshTests;
import net.stracciatella.pathfinding.test.PathWalkerTests;
import net.stracciatella.pathfinding.travel.EnderPearlTravelMethod;
import net.stracciatella.pathfinding.travel.Journey;
import net.stracciatella.pathfinding.travel.Navigator;
import net.stracciatella.pathfinding.travel.WalkTravelMethod;
import net.stracciatella.testing.runner.TestRunner;
import org.joml.Matrix4f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class PathfindingModule implements Module {

    @Task(lifeCycle = LifeCycle.STARTED)
    public void init() {
        PathWalker.loadConfig();
        PathDisplay.loadConfig();
        PathDisplay display = new PathDisplay();
        PathCommands commands = new PathCommands();
        commands.register();
        ClientTickEvents.START_CLIENT_TICK.register(MeshManager::flushInvalidations);
        ClientTickEvents.END_CLIENT_TICK.register(PathWalker::tick);
        TestRunner.instance().registerSuite(PathWalkerTests.class);
        TestRunner.instance().registerSuite(EnderPearlTests.class);
        TestRunner.instance().registerSuite(JourneyTests.class);
        TestRunner.instance().registerSuite(MeshTests.class);

        // Journey drives PathWalker the way Navigator does, so it is ticked here
        // rather than by its callers — one journey at a time, one tick source.
        ClientTickEvents.END_CLIENT_TICK.register(Journey::tick);

        Navigator.register(new EnderPearlTravelMethod());
        Navigator.register(new WalkTravelMethod());
        ClientTickEvents.END_CLIENT_TICK.register(Navigator::tick);
        new NavigateCommands().register();
    }


}
