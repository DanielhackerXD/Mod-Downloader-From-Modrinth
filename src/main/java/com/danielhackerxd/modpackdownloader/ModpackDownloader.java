package com.danielhackerxd.modpackdownloader;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.List;

@Mod("mdfm")
public class ModpackDownloader {

    private static boolean promptShownThisSession = false;

    // Forge 1.20.1-47.4.x+ deprecated the static FMLJavaModLoadingContext.get()
    // in favor of receiving the context directly as a constructor parameter
    // (this matches NeoForge's approach). There isn't yet a non-deprecated
    // equivalent for ModLoadingContext.get() in 1.20.1, so that one call
    // still uses it (it only produces a deprecation warning, not an error).
    public ModpackDownloader(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(this::clientSetup);

        // This is a client-only mod: it has nothing to do on a dedicated
        // server, and its mods/ folder logic only makes sense for the
        // machine actually running the game. Declaring this DisplayTest
        // tells Forge's network handshake to ignore this mod entirely
        // when checking client/server mod list compatibility, so players
        // don't get an "incompatible mod list" warning when joining a
        // server that doesn't have it (or a vanilla server).
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true));
    }

    private void clientSetup(FMLClientSetupEvent event) {
        // If mods.json doesn't exist yet, create the template and don't
        // show any warning on this launch (this is the first time the mod
        // is installed and the pack author hasn't configured it yet).
        if (!DownloaderConfig.configExists()) {
            DownloaderConfig.createTemplateIfMissing();
            return;
        }

        MinecraftForge.EVENT_BUS.register(new TitleScreenHook());
    }

    public static class TitleScreenHook {

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void onScreenOpening(ScreenEvent.Opening event) {
            if (promptShownThisSession) {
                return;
            }
            if (!(event.getNewScreen() instanceof TitleScreen)) {
                return;
            }

            List<ModEntry> entries = DownloaderConfig.load();
            if (entries.isEmpty()) {
                return;
            }

            if (ModDownloader.allInstalled(entries)) {
                // All mods are already installed, no need to bother the player.
                return;
            }

            promptShownThisSession = true;
            event.setNewScreen(new DownloadPromptScreen(entries));
        }
    }
}
