package nl.requios.effortlessbuilding.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anchor toolbar: Ctrl+scroll picks a tool, Shift+scroll uses it, while a preview is anchored in the world. */
@Mixin(MouseHandler.class)
public class MixinMouseHandler {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void effortlessbuilding$anchorScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) return;
        if (BuildPipelineClient.handleAnchorScroll(Screen.hasControlDown(), Screen.hasShiftDown(), yOffset)) ci.cancel();
    }
}
