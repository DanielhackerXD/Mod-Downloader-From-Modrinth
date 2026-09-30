package com.danielhackerxd.modpackdownloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class DownloaderConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("mdfm");
    private static final Path MODS_JSON = CONFIG_DIR.resolve("mods.json");

    /**
     * @return true if mods.json already existed before calling this method.
     *         If it didn't exist, a sample template is created and false is returned.
     */
    public static boolean configExists() {
        return Files.exists(MODS_JSON);
    }

    public static void createTemplateIfMissing() {
        if (Files.exists(MODS_JSON)) {
            return;
        }
        try {
            Files.createDirectories(CONFIG_DIR);

            ModEntry example1 = new ModEntry(
                    "Example mod name 1",
                    "https://modrinth.com/mod/example-mod/version/AbCdEfGh",
                    "example-mod-1.20.1.jar"
            );
            ModEntry example2 = new ModEntry(
                    "Example mod name 2",
                    "https://cdn.modrinth.com/data/XXXXXXXX/versions/YYYYYYYY/example-mod-2-1.20.1.jar",
                    "example-mod-2-1.20.1.jar"
            );

            List<ModEntry> template = List.of(example1, example2);

            try (Writer writer = Files.newBufferedWriter(MODS_JSON, StandardCharsets.UTF_8)) {
                GSON.toJson(template, writer);
            }

            // Explanatory file next to the template, so it's clear that
            // mods.json needs to be edited before distributing the modpack.
            Path readme = CONFIG_DIR.resolve("README.txt");
            String readmeText =
                    "MDFM (Mod Downloader From Modrinth)\n" +
                    "This mods.json file is an auto-generated TEMPLATE.\n" +
                    "As long as the player has this sample template (or the file\n" +
                    "doesn't exist), NO warning screen will be shown on game launch.\n\n" +
                    "To prepare your modpack:\n" +
                    "1. Edit mods.json and replace the examples with the real mods.\n" +
                    "   - name: the name shown to the player.\n" +
                    "   - url: the mod's Modrinth download link. This can be a normal\n" +
                    "     Modrinth version page link (e.g. modrinth.com/mod/x/version/y),\n" +
                    "     which is automatically resolved to the real CDN download link\n" +
                    "     via Modrinth's public API, or an already-direct link hosted on\n" +
                    "     cdn.modrinth.com. For security reasons, ONLY modrinth.com and\n" +
                    "     cdn.modrinth.com links are accepted - any other URL is refused.\n" +
                    "   - fileName: the file name it will be saved as in the mods/ folder.\n" +
                    "   - sha1 / sha512 (optional): expected hash of the downloaded file.\n" +
                    "     If provided, the download is rejected unless it matches exactly.\n" +
                    "2. Distribute the pack with this edited mods.json already included.\n" +
                    "3. On every launch, the mod checks whether those files already exist\n" +
                    "   in mods/. If any are missing, the warning screen is shown and the\n" +
                    "   game cannot proceed until they are downloaded.\n" +
                    "4. Once all files exist in mods/, the screen will not appear again.\n";
            Files.writeString(readme, readmeText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not create the mdfm configuration template", e);
        }
    }

    public static List<ModEntry> load() {
        if (!Files.exists(MODS_JSON)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(MODS_JSON, StandardCharsets.UTF_8)) {
            ModEntry[] arr = GSON.fromJson(reader, ModEntry[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (IOException e) {
            throw new RuntimeException("Could not read mdfm's mods.json", e);
        }
    }
}
