package net.stracciatella.pathfinding.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ChunkPos;)V", at = @At("TAIL"))
    private void onChunkLoad(net.minecraft.world.level.Level level, net.minecraft.world.level.ChunkPos pos, CallbackInfo ci) {
        // TODO: trigger mesh generation on chunk load
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void onBlockUpdate(BlockPos pos, BlockState state, int i, CallbackInfoReturnable<BlockState> cir) {
        // TODO: re-generate or update mesh slice when a block changes
    }
}
