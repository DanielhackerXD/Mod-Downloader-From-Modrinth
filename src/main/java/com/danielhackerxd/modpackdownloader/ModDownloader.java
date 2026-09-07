package com.danielhackerxd.modpackdownloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModDownloader {

    private static final Logger LOGGER = LogManager.getLogger("MDFM");


    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};


    private static final Pattern MODRINTH_VERSION_PAGE =
            Pattern.compile("^https?://modrinth\\.com/[^/]+/([^/]+)/version/([^/?#]+)/?$");

    private static final Path MODS_DIR = FMLPaths.MODSDIR.get();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();


    private static final int RATE_LIMIT_MAX_RETRIES = 3;
    private static final long RATE_LIMIT_BASE_DELAY_MS = 4000;


    public static boolean isAlreadyInstalled(ModEntry entry) {
        return Files.exists(MODS_DIR.resolve(entry.fileName));
    }

    public static boolean allInstalled(List<ModEntry> entries) {
        for (ModEntry e : entries) {
            if (!isAlreadyInstalled(e)) {
                return false;
            }
        }
        return true;
    }


    public static void downloadAllAsync(List<ModEntry> entries,
                                         Consumer<ModEntry> onEntryUpdate,
                                         Consumer<List<ModEntry>> onFinished) {
        Thread thread = new Thread(() -> {
            java.util.List<ModEntry> failed = new java.util.ArrayList<>();
            try {
                Files.createDirectories(MODS_DIR);
            } catch (IOException e) {
                for (ModEntry entry : entries) {
                    entry.status = ModEntry.DownloadStatus.FAILED;
                    onEntryUpdate.accept(entry);
                    failed.add(entry);
                }
                onFinished.accept(failed);
                return;
            }

            for (ModEntry entry : entries) {
                if (!entry.selected) {
                    continue;
                }
                if (isAlreadyInstalled(entry)) {
                    entry.status = ModEntry.DownloadStatus.DONE;
                    onEntryUpdate.accept(entry);
                    continue;
                }

                entry.status = ModEntry.DownloadStatus.DOWNLOADING;
                onEntryUpdate.accept(entry);

                boolean ok = downloadOne(entry);

                entry.status = ok ? ModEntry.DownloadStatus.DONE : ModEntry.DownloadStatus.FAILED;
                onEntryUpdate.accept(entry);
                if (!ok) {
                    failed.add(entry);
                }
            }

            onFinished.accept(failed);
        }, "mdfm-download-thread");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean downloadOne(ModEntry entry) {
        Path target = MODS_DIR.resolve(entry.fileName);
        Path tempFile = MODS_DIR.resolve(entry.fileName + ".part");

        try {
            String actualUrl = resolveActualDownloadUrl(entry.url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(actualUrl))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "ModpackDownloader/1.0 (Minecraft Forge 1.20.1)")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = sendWithRateLimitRetry(request, HttpResponse.BodyHandlers.ofInputStream(),
                    "[" + entry.fileName + "] download");

            if (response.statusCode() == 429) {
                LOGGER.warn("[{}] download failed: still rate-limited (HTTP 429) after retrying.", entry.fileName);
                return false;
            }

            if (response.statusCode() != 200) {
                LOGGER.warn("[{}] download failed: HTTP {} from {}", entry.fileName, response.statusCode(), entry.url);
                return false;
            }


            Optional<String> contentType = response.headers().firstValue("Content-Type");
            if (contentType.isPresent() && contentType.get().toLowerCase().contains("text/html")) {
                LOGGER.warn("[{}] the configured URL returned HTML instead of a file. " +
                        "Make sure it is the DIRECT download link for the .jar, not the mod's page: {}",
                        entry.fileName, entry.url);
                return false;
            }

            try (InputStream in = response.body()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }


            if (Files.size(tempFile) < 1024) {
                LOGGER.warn("[{}] downloaded file is too small ({} bytes), likely an error page.",
                        entry.fileName, Files.size(tempFile));
                Files.deleteIfExists(tempFile);
                return false;
            }


            if (!hasValidZipSignature(tempFile)) {
                LOGGER.warn("[{}] the downloaded file is not a valid ZIP/JAR (wrong signature). " +
                        "Check that the URL in mods.json is the direct download link for the .jar: {}",
                        entry.fileName, entry.url);
                Files.deleteIfExists(tempFile);
                return false;
            }

            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[{}] exception while downloading from {}: {}", entry.fileName, entry.url, e.toString());
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            return false;
        }
    }


    private static <T> HttpResponse<T> sendWithRateLimitRetry(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler,
                                                                String description) throws IOException, InterruptedException {
        HttpResponse<T> response = CLIENT.send(request, bodyHandler);
        int attempt = 0;
        while (response.statusCode() == 429 && attempt < RATE_LIMIT_MAX_RETRIES) {
            attempt++;
            long delayMs = RATE_LIMIT_BASE_DELAY_MS * attempt;
            LOGGER.info("{}: rate-limited (HTTP 429), retrying in {} ms (attempt {}/{})...",
                    description, delayMs, attempt, RATE_LIMIT_MAX_RETRIES);
            Thread.sleep(delayMs);
            response = CLIENT.send(request, bodyHandler);
        }
        return response;
    }


    private static String resolveActualDownloadUrl(String originalUrl) {
        Matcher modrinthMatcher = MODRINTH_VERSION_PAGE.matcher(originalUrl);
        if (modrinthMatcher.matches()) {
            return resolveModrinthUrl(originalUrl, modrinthMatcher.group(1), modrinthMatcher.group(2));
        }
        return originalUrl;
    }


    private static String resolveModrinthUrl(String originalUrl, String projectSlug, String versionIdentifier) {
        try {
            JsonObject versionJson = fetchModrinthVersionById(versionIdentifier);

            if (versionJson == null) {

                versionJson = fetchModrinthVersionByNumber(projectSlug, versionIdentifier);
            }

            if (versionJson == null) {
                LOGGER.warn("Could not resolve Modrinth version '{}' for project '{}' as either an id or a " +
                        "version number, using the original URL.", versionIdentifier, projectSlug);
                return originalUrl;
            }

            JsonArray files = versionJson.getAsJsonArray("files");
            if (files == null || files.isEmpty()) {
                return originalUrl;
            }


            JsonObject chosen = null;
            for (JsonElement fileElement : files) {
                JsonObject fileObj = fileElement.getAsJsonObject();
                if (fileObj.has("primary") && fileObj.get("primary").getAsBoolean()) {
                    chosen = fileObj;
                    break;
                }
            }
            if (chosen == null) {
                chosen = files.get(0).getAsJsonObject();
            }

            if (chosen.has("url")) {
                String resolved = chosen.get("url").getAsString();
                LOGGER.info("Resolved Modrinth URL: {} -> {}", originalUrl, resolved);
                return resolved;
            }
        } catch (Exception e) {
            LOGGER.warn("Error resolving Modrinth version '{}' for project '{}': {}. Using the original URL.",
                    versionIdentifier, projectSlug, e.toString());
        }

        return originalUrl;
    }


    private static JsonObject fetchModrinthVersionById(String versionId) throws IOException, InterruptedException {
        String apiUrl = "https://api.modrinth.com/v2/version/" + versionId;

        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ModpackDownloader/1.0 (Minecraft Forge 1.20.1)")
                .GET()
                .build();

        HttpResponse<String> apiResponse = sendWithRateLimitRetry(apiRequest, HttpResponse.BodyHandlers.ofString(),
                "Modrinth version lookup for " + versionId);

        if (apiResponse.statusCode() == 400 || apiResponse.statusCode() == 404) {

            return null;
        }
        if (apiResponse.statusCode() != 200) {
            LOGGER.warn("Modrinth version lookup for '{}' failed: HTTP {}", versionId, apiResponse.statusCode());
            return null;
        }

        return JsonParser.parseString(apiResponse.body()).getAsJsonObject();
    }


    private static JsonObject fetchModrinthVersionByNumber(String projectSlug, String versionNumber)
            throws IOException, InterruptedException {
        String apiUrl = "https://api.modrinth.com/v2/project/" + projectSlug + "/version";

        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ModpackDownloader/1.0 (Minecraft Forge 1.20.1)")
                .GET()
                .build();

        HttpResponse<String> apiResponse = sendWithRateLimitRetry(apiRequest, HttpResponse.BodyHandlers.ofString(),
                "Modrinth version list for project '" + projectSlug + "'");

        if (apiResponse.statusCode() != 200) {
            LOGGER.warn("Modrinth version list lookup for project '{}' failed: HTTP {}",
                    projectSlug, apiResponse.statusCode());
            return null;
        }

        JsonArray versions = JsonParser.parseString(apiResponse.body()).getAsJsonArray();
        for (JsonElement element : versions) {
            JsonObject versionObj = element.getAsJsonObject();
            if (versionObj.has("version_number")
                    && versionObj.get("version_number").getAsString().equals(versionNumber)) {
                return versionObj;
            }
        }

        LOGGER.warn("No version with version_number '{}' found for Modrinth project '{}'.", versionNumber, projectSlug);
        return null;
    }

    private static boolean hasValidZipSignature(Path file) {
        byte[] header = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(header, 0, 4);
            if (read < 4) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return java.util.Arrays.equals(header, ZIP_MAGIC);
    }
}
