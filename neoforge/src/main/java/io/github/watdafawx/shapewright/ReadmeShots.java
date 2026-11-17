package io.github.watdafawx.shapewright;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import io.github.watdafawx.shapewright.buildmode.BuildModeEnum;
import io.github.watdafawx.shapewright.buildmode.BuildModes;
import io.github.watdafawx.shapewright.buildpipeline.BuildPipelineClient;
import io.github.watdafawx.shapewright.modifier.ArrayModifier;
import io.github.watdafawx.shapewright.modifier.MirrorModifier;
import io.github.watdafawx.shapewright.modifier.ModifierSystem;
import io.github.watdafawx.shapewright.modifier.RadialMirrorModifier;
import io.github.watdafawx.shapewright.screen.ClientConfigScreen;
import io.github.watdafawx.shapewright.screen.ModifiersScreen;
import io.github.watdafawx.shapewright.screen.RadialMenu;
import io.github.watdafawx.shapewright.screen.ServerConfigScreen;
import io.github.watdafawx.shapewright.shape.ShapeClientState;
import io.github.watdafawx.shapewright.shape.ShapeParams;
import io.github.watdafawx.shapewright.shape.ShapeType;

import java.util.List;

/** Screenshots of the original Effortless Building features, for the README. Names: readme-*. */
final class ReadmeShots {

    private ReadmeShots() {}

    private static void window(int w, int h, int scale) {
        Minecraft mc = Minecraft.getInstance();
        mc.getWindow().setWindowed(w, h);
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
    }

    private static List<String> open(Class<?> screen) {
        return screen.isInstance(Minecraft.getInstance().screen) ? List.of() : List.of(screen.getSimpleName() + " closed");
    }

    static SelfTest.Seq scenario() {
        int[] savedScale = {0};
        return new SelfTest.Seq("readme-shots")
                .onServerRun(p -> {
                    SelfTest.clearPlayer(p);
                    p.setGameMode(GameType.CREATIVE);
                    p.getInventory().setItem(0, new ItemStack(Items.STONE_BRICKS, 64));
                    p.teleportTo(400.5, -60, 400.5);
                })
                .waitTicks(20)
                .run(() -> {
                    savedScale[0] = Minecraft.getInstance().options.guiScale().get();
                    window(1920, 1080, 3);
                    ModifierSystem.CLIENT.clearModifiers();
                    BuildModes.CLIENT.setBuildMode(BuildModeEnum.SPHERE);
                    Minecraft.getInstance().getToasts().clear();
                    RadialMenu.keepOpen = true;
                    Minecraft.getInstance().setScreen(RadialMenu.instance);
                })
                .waitTicks(8)
                .check("readme-radial-menu", () -> open(RadialMenu.class))
                .run(() -> {
                    RadialMenu.keepOpen = false;
                    MirrorModifier mirror = new MirrorModifier();
                    mirror.originX = 400.5;
                    mirror.mirrorX = true;
                    mirror.mirrorZ = true;
                    mirror.mirrorY = false;
                    ArrayModifier array = new ArrayModifier();
                    array.count = 3;
                    array.offsetX = 4;
                    RadialMirrorModifier radial = new RadialMirrorModifier();
                    radial.slices = 6;
                    ModifierSystem.CLIENT.addModifier(mirror);
                    ModifierSystem.CLIENT.addModifier(array);
                    ModifierSystem.CLIENT.addModifier(radial);
                    Minecraft.getInstance().getToasts().clear();
                    Minecraft.getInstance().setScreen(new ModifiersScreen());
                })
                .waitTicks(10)
                .run(() -> Minecraft.getInstance().screen.mouseClicked(490 / 3.0, 275 / 3.0, 0)) // select Mirror: its settings show on the right
                .waitTicks(6)
                .check("readme-modifiers", () -> open(ModifiersScreen.class))
                .run(() -> Minecraft.getInstance().setScreen(new ServerConfigScreen()))
                .waitTicks(10)
                .check("readme-server-config", () -> open(ServerConfigScreen.class))
                .run(() -> Minecraft.getInstance().setScreen(new ClientConfigScreen()))
                .waitTicks(10)
                .check("readme-client-config", () -> open(ClientConfigScreen.class))
                // In world: a pyramid mirrored and arrayed, preview locked
                .onServerRun(p -> {
                    p.getAbilities().mayfly = true;
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                    p.teleportTo(400.5, -50, 386.5);
                })
                .waitTicks(20)
                .run(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(null);
                    ModifierSystem.CLIENT.clearModifiers();
                    MirrorModifier mirror = new MirrorModifier();
                    mirror.originX = 405.5;
                    mirror.mirrorX = true;
                    mirror.mirrorZ = false;
                    mirror.mirrorY = false;
                    ModifierSystem.CLIENT.addModifier(mirror);
                    mc.player.setXRot(35);
                    mc.player.setYRot(0);
                    mc.options.hideGui = false;
                    BuildModes.CLIENT.setBuildMode(BuildModeEnum.SHAPE);
                    ShapeClientState.setActive(ShapeParams.defaults(ShapeType.PYRAMID).withSize(5));
                })
                .waitTicks(SelfTest.SETTLE)
                .run(BuildPipelineClient::togglePreviewLock)
                .check("readme-modifier-world", () -> BuildPipelineClient.previewLocked ? List.of() : List.of("preview did not lock"))
                .run(() -> {
                    BuildPipelineClient.togglePreviewLock();
                    ModifierSystem.CLIENT.clearModifiers();
                    Minecraft mc = Minecraft.getInstance();
                    mc.options.guiScale().set(savedScale[0]);
                    mc.resizeDisplay();
                });
    }
}
