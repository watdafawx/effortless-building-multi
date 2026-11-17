package io.github.watdafawx.shapewright;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import io.github.watdafawx.shapewright.screen.ClientConfigScreen;

/**
 * Isolated in its own class so that {@code IConfigScreenFactory} and {@code ClientConfigScreen}
 * are never loaded on a dedicated server (they reference client-only {@code Screen}).
 */
public final class NeoForgeConfigScreenRegistrar {

    public static void register(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> new ClientConfigScreen());
    }
}

