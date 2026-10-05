package net.stracciatella.pathfinding.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.logic.MeshManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ChunkPos;)V", at = @At("TAIL"))
    private void onChunkLoad(net.minecraft.world.level.Level level, net.minecraft.world.level.ChunkPos pos, CallbackInfo ci) {
        // Intentionally empty: meshes are built on-demand by
        // MeshManager.findOrBuildNearestNode, so eager generation on chunk
        // load would just burn CPU on chunks no bot ever uses.
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void onBlockUpdate(BlockPos pos, BlockState newState, int i, CallbackInfoReturnable<BlockState> cir) {
        BlockState oldState = cir.getReturnValue();
        if (oldState == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        LevelChunk self = (LevelChunk) (Object) this;
        // setBlockState fires on both server and client threads in single-
        // player. Only act on the client-side write — MeshManager.invalidateMesh
        // reads via Minecraft.getInstance().level, so we'd otherwise regenerate
        // against stale client data when the server thread fires first.
        if (self.getLevel() != mc.level) {
            return;
        }
        // Only regenerate when what the mesh reads changed: collision or fluid.
        // Sub-state edits (redstone power, growth stages, leaf decay) don't
        // change walkability and we'd just waste a full chunk re-walk. Asking
        // isAir alone, as this did, missed a block placed into grass or snow —
        // neither is air, and the mesh kept walking through the new block.
        if (oldState.isAir() == newState.isAir()
                && oldState.getFluidState().isEmpty() == newState.getFluidState().isEmpty()
                && oldState.getCollisionShape(mc.level, pos).isEmpty()
                        == newState.getCollisionShape(mc.level, pos).isEmpty()) {
            return;
        }

        ChunkCoordinate chunkCoord = new ChunkCoordinate(pos.getX() >> 4, pos.getZ() >> 4);
        // Skip the full O(chunk) rebuild unless some entity actually has a
        // mesh for this chunk. Most edits are far from any active bot.
        for (var entityMeshes : MeshManager.meshes.values()) {
            if (entityMeshes.containsKey(chunkCoord)) {
                MeshManager.invalidateMesh(chunkCoord);
                break;
            }
        }
    }
}
