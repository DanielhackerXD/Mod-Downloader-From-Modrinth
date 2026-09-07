package com.danielhackerxd.modpackdownloader;


public class ModEntry {
    public String name;
    public String url;
    public String fileName;


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
