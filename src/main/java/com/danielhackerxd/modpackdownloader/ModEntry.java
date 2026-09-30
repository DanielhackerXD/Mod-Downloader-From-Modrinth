package com.danielhackerxd.modpackdownloader;

/**
 * Represents a mod that must be downloaded and installed into the mods/ folder.
 *
 * name     -> name shown to the player
 * url      -> download link (a direct .jar link, or a normal Modrinth
 *             version page link, which is automatically resolved to the
 *             real download link, see ModDownloader.resolveActualDownloadUrl)
 * fileName -> the file name it will be saved as inside mods/
 */
public class ModEntry {
    public String name;
    public String url;
    public String fileName;

    // Optional integrity check: if set, the downloaded file's hash must
    // match, or the download is treated as failed. At least one of the two
    // may be provided; if both are, both must match. Either may be left
    // null/omitted in mods.json.
    public String sha1;
    public String sha512;

    // Runtime-only field, not persisted in the JSON file.
    public transient boolean selected = true;
    public transient DownloadStatus status = DownloadStatus.PENDING;

    public ModEntry() {
    }

    public ModEntry(String name, String url, String fileName) {
        this.name = name;
        this.url = url;
        this.fileName = fileName;
    }

    public enum DownloadStatus {
        PENDING,
        DOWNLOADING,
        DONE,
        FAILED
    }
}
