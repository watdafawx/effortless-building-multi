package nl.requios.effortlessbuilding.utilities;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import nl.requios.effortlessbuilding.mixin.KeyMappingAccessor;
import nl.requios.effortlessbuilding.screen.AnchorViewScreen;
import nl.requios.effortlessbuilding.screen.ShapeGeneratorScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Central holder for all mod keybindings.
 * Instances are created here; loader-specific code registers them.
 */
public class KeyBindings {

    public static final String CATEGORY = "key.categories.effortlessbuilding";
    
    public static KeyMapping openRadialMenu = new KeyMapping(
            "key.effortlessbuilding.open_radial_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            CATEGORY
    );
    
    public static KeyMapping openModifiersScreen = new KeyMapping(
            "key.effortlessbuilding.open_modifiers_screen",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_ADD,
            CATEGORY
    );

    public static KeyMapping undo = new KeyMapping(
            "key.effortlessbuilding.undo.desc",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY
    );

    public static KeyMapping redo = new KeyMapping(
            "key.effortlessbuilding.redo.desc",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            CATEGORY
    );

    public static KeyMapping openShapeGenerator = new KeyMapping(
            "key.effortlessbuilding.open_shape_generator",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_MULTIPLY,
            CATEGORY
    );

    /** Freezes the ghost preview in place; unbound by default (the radial menu has a button too). */
    public static KeyMapping anchorPreview = new KeyMapping(
            "key.effortlessbuilding.anchor_preview",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY
    );

    /** Opens the overview of the anchored preview; unbound by default (also in the radial menu). */
    public static KeyMapping anchorOverview = new KeyMapping(
            "key.effortlessbuilding.anchor_overview", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    // Move the anchored preview: arrows go relative to where you look, Page Up/Down go up and down
    public static KeyMapping anchorForward = new KeyMapping(
            "key.effortlessbuilding.anchor_forward", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, CATEGORY);
    public static KeyMapping anchorBack = new KeyMapping(
            "key.effortlessbuilding.anchor_back", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, CATEGORY);
    public static KeyMapping anchorLeft = new KeyMapping(
            "key.effortlessbuilding.anchor_left", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, CATEGORY);
    public static KeyMapping anchorRight = new KeyMapping(
            "key.effortlessbuilding.anchor_right", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, CATEGORY);
    public static KeyMapping anchorUp = new KeyMapping(
            "key.effortlessbuilding.anchor_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_UP, CATEGORY);
    public static KeyMapping anchorDown = new KeyMapping(
            "key.effortlessbuilding.anchor_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_DOWN, CATEGORY);

    /** All keys the loaders register besides the original four. */
    public static final KeyMapping[] SHAPE_KEYS = {openShapeGenerator, anchorPreview, anchorOverview,
            anchorForward, anchorBack, anchorLeft, anchorRight, anchorUp, anchorDown};

    /** Called every client tick by both loaders. */
    public static void handleShapeKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (openShapeGenerator.consumeClick()) {
            mc.setScreen(new ShapeGeneratorScreen());
        }
        while (anchorPreview.consumeClick()) {
            BuildPipelineClient.togglePreviewLockWithMessage();
        }
        if (anchorOverview.consumeClick()) {
            mc.setScreen(new AnchorViewScreen());
        }
        // Nudging only means something while a preview is anchored; otherwise let the presses go
        boolean anchored = BuildPipelineClient.previewLocked;
        while (anchorForward.consumeClick()) if (anchored) BuildPipelineClient.nudgeAnchorRelative(1, 0, 0);
        while (anchorBack.consumeClick()) if (anchored) BuildPipelineClient.nudgeAnchorRelative(-1, 0, 0);
        while (anchorLeft.consumeClick()) if (anchored) BuildPipelineClient.nudgeAnchorRelative(0, -1, 0);
        while (anchorRight.consumeClick()) if (anchored) BuildPipelineClient.nudgeAnchorRelative(0, 1, 0);
        while (anchorUp.consumeClick()) if (anchored) BuildPipelineClient.nudgeAnchorRelative(0, 0, 1);
        while (anchorDown.consumeClick()) if (anchored) BuildPipelineClient.nudgeAnchorRelative(0, 0, -1);
    }

    /**
     * Checks if the physical key bound to a KeyMapping is currently held down.
     * Unlike KeyMapping.isDown(), this works even when a Screen is open.
     */
    public static boolean isKeyDown(KeyMapping keyMapping) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, ((KeyMappingAccessor) keyMapping).effortlessbuilding$getKey().getValue());
    }
}
