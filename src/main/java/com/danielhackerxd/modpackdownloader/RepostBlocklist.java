package com.danielhackerxd.modpackdownloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extra safety net on top of the host allowlist in ModDownloader: cross-checks
 * a resolved download URL against the public StopModReposts database
 * (https://stopmodreposts.org), a community-maintained list of sites known to
 * illegally re-host mods. Since every download this mod performs is already
 * restricted to cdn.modrinth.com by the allowlist, this mostly guards against
 * the (very unlikely) case of that domain itself ever being flagged - but
 * checking it costs nothing and is good practice for anything that downloads
 * files on a player's behalf.
 *
 * Fails open: if the database can't be fetched (offline, API down, etc.),
 * downloads are allowed to proceed rather than being blocked by an
 * unrelated network hiccup.
 */
public class RepostBlocklist {

    private static final Logger LOGGER = LogManager.getLogger("MDFM");
    private static final String SITES_URL = "https://api.stopmodreposts.org/sites.json";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile List<String> flaggedDomains = null;
    private static final Object LOAD_LOCK = new Object();

    /**
     * Returns true if the given URL's host matches (or is a subdomain of) a
     * domain flagged in the StopModReposts database. Loads and caches the
     * database on first use.
     */
    public static boolean isFlagged(String url) {
        List<String> domains = getFlaggedDomains();
        if (domains.isEmpty()) {
            return false;
        }

        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);

        for (String flagged : domains) {
            if (host.equals(flagged) || host.endsWith("." + flagged)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> getFlaggedDomains() {
        List<String> loaded = flaggedDomains;
        if (loaded != null) {
            return loaded;
        }

        synchronized (LOAD_LOCK) {
            if (flaggedDomains != null) {
                return flaggedDomains;
            }
            flaggedDomains = fetchFlaggedDomains();
            return flaggedDomains;
        }
    }

    private static List<String> fetchFlaggedDomains() {
        List<String> result = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SITES_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "MDFM/1.0 (Minecraft Forge 1.20.1)")
                    .GET()
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("Could not load the StopModReposts database (HTTP {}); skipping this safety check.",
                        response.statusCode());
                return result;
            }

            JsonArray entries = JsonParser.parseString(response.body()).getAsJsonArray();
            for (JsonElement element : entries) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("domain")) {
                    result.add(obj.get("domain").getAsString().toLowerCase(Locale.ROOT));
                }
            }
            LOGGER.info("Loaded {} flagged domain(s) from the StopModReposts database.", result.size());
        } catch (Exception e) {
            LOGGER.warn("Could not load the StopModReposts database: {}; skipping this safety check.", e.toString());
        }
        return result;
    }
}
