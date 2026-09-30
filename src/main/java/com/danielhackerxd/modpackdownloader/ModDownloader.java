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
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModDownloader {

    private static final Logger LOGGER = LogManager.getLogger("MDFM");

    // Magic bytes of a valid ZIP/JAR file: 'P' 'K' 0x03 0x04
    // (there are also PK\x05\x06 and PK\x07\x08 variants for empty or
    // multi-volume zips, but a normal jar always starts with PK\x03\x04).
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    // Matches Modrinth PAGE links such as:
    //   https://modrinth.com/mod/entityculling/version/MloBcsQQ
    //   https://modrinth.com/mod/appleskin/version/2.5.1+mc1.20.1
    // Group 1 = project slug, group 2 = version identifier, which can be
    // either the real Modrinth version id (e.g. "MloBcsQQ") or the
    // human-readable version_number (e.g. "2.5.1+mc1.20.1") - Modrinth's
    // website accepts both in the URL, but only the real id works directly
    // against the /v2/version/{id} API endpoint. See resolveModrinthUrl.
    private static final Pattern MODRINTH_VERSION_PAGE =
            Pattern.compile("^https?://modrinth\\.com/[^/]+/([^/]+)/version/([^/?#]+)/?$");

    // SECURITY: the only hosts a final download request is ever allowed to
    // go to. This applies to the fully-resolved URL, not just the
    // mods.json entry, so a page link that resolves somewhere unexpected
    // is caught too. Without this check, a mods.json pointing to an
    // arbitrary URL would have been downloaded and loaded as a mod jar
    // with no restriction at all - this allowlist is what prevents that.
    private static final Set<String> ALLOWED_DOWNLOAD_HOSTS = Set.of(
            "cdn.modrinth.com"
    );

    private static final Path MODS_DIR = FMLPaths.MODSDIR.get();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // Modrinth's public API is generous, but retrying a couple of times on a
    // rate limit still turns a transient hiccup into a short wait instead of
    // a hard failure.
    private static final int RATE_LIMIT_MAX_RETRIES = 3;
    private static final long RATE_LIMIT_BASE_DELAY_MS = 4000;

    /**
     * Checks, without downloading anything, whether the files in the list
     * are already present in the mods/ folder.
     */
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

    /**
     * Downloads all selected mods sequentially, on a background thread.
     * Calls onEntryUpdate every time a ModEntry's status changes, and
     * onFinished when done with the list of failures (empty if everything succeeded).
     */
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

            // SECURITY: refuse to download from anything outside the
            // allowlisted Modrinth CDN host, regardless of what was
            // configured in mods.json or what a page link resolved to.
            if (!isAllowedDownloadHost(actualUrl)) {
                LOGGER.warn("[{}] refusing to download: '{}' is not an allowed host (only {} is permitted). " +
                        "mods.json entries must be modrinth.com page links or direct cdn.modrinth.com links.",
                        entry.fileName, actualUrl, ALLOWED_DOWNLOAD_HOSTS);
                return false;
            }

            // Extra safety net on top of the host allowlist above: cross-check
            // against the public StopModReposts database of sites known to
            // illegally re-host mods. Given the allowlist already restricts
            // downloads to cdn.modrinth.com, this mainly matters as
            // defense-in-depth rather than as the primary protection.
            if (RepostBlocklist.isFlagged(actualUrl)) {
                LOGGER.warn("[{}] refusing to download: '{}' is flagged in the StopModReposts database " +
                        "(https://stopmodreposts.org) as a known illegal mod repost site.", entry.fileName, actualUrl);
                return false;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(actualUrl))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "MDFM/1.0 (Minecraft Forge 1.20.1)")
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

            // If the server says this is HTML, the URL almost certainly
            // points to a page instead of the direct .jar file. Bail out
            // before wasting bandwidth.
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

            // Check 1: reasonable minimum size.
            if (Files.size(tempFile) < 1024) {
                LOGGER.warn("[{}] downloaded file is too small ({} bytes), likely an error page.",
                        entry.fileName, Files.size(tempFile));
                Files.deleteIfExists(tempFile);
                return false;
            }

            // Check 2 (the important one): a .jar is a .zip under the hood,
            // so its first 4 bytes must be the PK\x03\x04 signature. If
            // they aren't, what was downloaded is not a valid jar (usually
            // it's HTML from an intermediate download page) and Forge
            // would reject it with "unknown format or damaged file".
            if (!hasValidZipSignature(tempFile)) {
                LOGGER.warn("[{}] the downloaded file is not a valid ZIP/JAR (wrong signature). " +
                        "Check that the URL in mods.json is the direct download link for the .jar: {}",
                        entry.fileName, entry.url);
                Files.deleteIfExists(tempFile);
                return false;
            }

            // Check 3 (optional): if mods.json declared an expected hash for
            // this mod, the downloaded bytes must match it exactly. Unlike
            // checks 1/2, which only confirm "this is *a* valid jar", this
            // confirms it's the *specific* file the pack author intended.
            if (!verifyExpectedHashes(entry, tempFile)) {
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

    /**
     * SECURITY: returns true only if the given URL's host is exactly one of
     * ALLOWED_DOWNLOAD_HOSTS. This is checked against the fully-resolved
     * download URL (after any Modrinth page resolution), so there is no way
     * for a mods.json entry - whether a page link or a "direct" link - to
     * cause a download from anywhere outside Modrinth's own CDN.
     */
    private static boolean isAllowedDownloadHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && ALLOWED_DOWNLOAD_HOSTS.contains(host.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sends a request, automatically retrying with a short backoff if the
     * response is HTTP 429 (rate limited). Returns the last response
     * received either way (a non-429 response, or the final 429 after
     * exhausting retries).
     */
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

    /**
     * If the URL configured by the pack author is a Modrinth version page,
     * resolves it into the real direct download link via Modrinth's public
     * API. Otherwise (already a direct link), the URL is used unchanged -
     * downloadOne() enforces the host allowlist afterwards either way, so
     * this method itself doesn't need to validate anything.
     */
    private static String resolveActualDownloadUrl(String originalUrl) {
        Matcher modrinthMatcher = MODRINTH_VERSION_PAGE.matcher(originalUrl);
        if (modrinthMatcher.matches()) {
            return resolveModrinthUrl(originalUrl, modrinthMatcher.group(1), modrinthMatcher.group(2));
        }
        return originalUrl;
    }

    /**
     * Resolves a Modrinth version page through its public API to get the
     * file's real CDN link. First tries treating the identifier as a real
     * Modrinth version id (the common case). If that fails with a 400 (the
     * identifier isn't a valid id - typically because it's actually a
     * human-readable version_number, e.g. "2.5.1+mc1.20.1"), falls back to
     * listing the project's versions and matching by version_number.
     * Falls back to the original URL if everything about this fails (the
     * host allowlist check and/or the ZIP signature check will then catch
     * the problem further down the line).
     */
    private static String resolveModrinthUrl(String originalUrl, String projectSlug, String versionIdentifier) {
        try {
            JsonObject versionJson = fetchModrinthVersionById(versionIdentifier);

            if (versionJson == null) {
                // Not a valid version id; try resolving it as a
                // version_number instead, by listing the project's versions.
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

            // Prefer the file marked as "primary"; if none is, use the
            // first one in the list.
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

    /**
     * Looks up a version directly by its real Modrinth id via
     * GET /v2/version/{id}. Returns null (not an exception) if the
     * identifier isn't a valid id (HTTP 400/404) so the caller can try the
     * version_number fallback instead.
     */
    private static JsonObject fetchModrinthVersionById(String versionId) throws IOException, InterruptedException {
        String apiUrl = "https://api.modrinth.com/v2/version/" + versionId;

        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "MDFM/1.0 (Minecraft Forge 1.20.1)")
                .GET()
                .build();

        HttpResponse<String> apiResponse = sendWithRateLimitRetry(apiRequest, HttpResponse.BodyHandlers.ofString(),
                "Modrinth version lookup for " + versionId);

        if (apiResponse.statusCode() == 400 || apiResponse.statusCode() == 404) {
            // Not a valid version id - likely a version_number instead.
            return null;
        }
        if (apiResponse.statusCode() != 200) {
            LOGGER.warn("Modrinth version lookup for '{}' failed: HTTP {}", versionId, apiResponse.statusCode());
            return null;
        }

        return JsonParser.parseString(apiResponse.body()).getAsJsonObject();
    }

    /**
     * Looks up a version by its human-readable version_number (e.g.
     * "2.5.1+mc1.20.1") by listing all versions of the project and finding
     * the one whose version_number matches. Needed because Modrinth's own
     * website accepts version_number in page URLs, but /v2/version/{id}
     * only accepts the real id.
     */
    private static JsonObject fetchModrinthVersionByNumber(String projectSlug, String versionNumber)
            throws IOException, InterruptedException {
        String apiUrl = "https://api.modrinth.com/v2/project/" + projectSlug + "/version";

        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "MDFM/1.0 (Minecraft Forge 1.20.1)")
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

    /**
     * Checks the downloaded file against whichever of entry.sha1 /
     * entry.sha512 were provided in mods.json. Returns true if there was
     * nothing to check, or everything provided matched; false (with a log
     * explaining which one failed) otherwise.
     */
    private static boolean verifyExpectedHashes(ModEntry entry, Path file) {
        if (entry.sha1 != null && !entry.sha1.isBlank()) {
            if (!hashMatches(file, "SHA-1", entry.sha1)) {
                LOGGER.warn("[{}] SHA-1 mismatch: the downloaded file does not match the hash configured in mods.json.",
                        entry.fileName);
                return false;
            }
        }
        if (entry.sha512 != null && !entry.sha512.isBlank()) {
            if (!hashMatches(file, "SHA-512", entry.sha512)) {
                LOGGER.warn("[{}] SHA-512 mismatch: the downloaded file does not match the hash configured in mods.json.",
                        entry.fileName);
                return false;
            }
        }
        return true;
    }

    private static boolean hashMatches(Path file, String algorithm, String expectedHex) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            String actualHex = bytesToHex(digest.digest());
            return actualHex.equalsIgnoreCase(expectedHex.trim());
        } catch (Exception e) {
            LOGGER.warn("Could not compute {} hash for {}: {}", algorithm, file.getFileName(), e.toString());
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
