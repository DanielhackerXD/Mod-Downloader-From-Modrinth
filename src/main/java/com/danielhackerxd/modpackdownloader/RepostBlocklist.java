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


public class RepostBlocklist {

    private static final Logger LOGGER = LogManager.getLogger("MDFM");
    private static final String SITES_URL = "https://api.stopmodreposts.org/sites.json";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile List<String> flaggedDomains = null;
    private static final Object LOAD_LOCK = new Object();


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
