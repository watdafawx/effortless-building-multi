package nl.requios.effortlessbuilding.utilities;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import nl.requios.effortlessbuilding.mixin.KeyMappingAccessor;
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

    /** Called every client tick by both loaders. */
    public static void handleShapeKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (openShapeGenerator.consumeClick()) {
            mc.setScreen(new ShapeGeneratorScreen());
        }
        while (anchorPreview.consumeClick()) {
            BuildPipelineClient.togglePreviewLockWithMessage();
        }
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
