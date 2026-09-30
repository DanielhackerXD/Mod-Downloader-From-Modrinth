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


    public ModpackDownloader(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(this::clientSetup);


        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true));
    }

    private void clientSetup(FMLClientSetupEvent event) {

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

                return;
            }

            promptShownThisSession = true;
            event.setNewScreen(new DownloadPromptScreen(entries));
        }
    }
}
