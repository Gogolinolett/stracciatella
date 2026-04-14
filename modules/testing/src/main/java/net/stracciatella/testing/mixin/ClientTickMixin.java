package net.stracciatella.testing.mixin;

import net.minecraft.client.Minecraft;
import net.stracciatella.testing.runner.TestRunner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class ClientTickMixin {
    @Unique
    private static boolean inExtraTick = false;

    @Inject(at = @At("TAIL"), method = "tick")
    private void onClientTick(CallbackInfo ci) {
        TestRunner.instance().onClientTick();

        if (!inExtraTick) {
            int multiplier = TestRunner.getTickMultiplier();
            if (multiplier > 1) {
                inExtraTick = true;
                for (int i = 1; i < multiplier; i++) {
                    ((Minecraft) (Object) this).tick();
                }
                inExtraTick = false;
            }
        }
    }

    @Inject(at = @At("HEAD"), method = "pauseGame", cancellable = true)
    private void onPauseGame(boolean pauseOnly, CallbackInfo ci) {
        if (TestRunner.instance().isRunning()) {
            ci.cancel();
        }
    }
}
