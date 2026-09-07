# MDFM - Mod Downloader From Modrinth (Forge 1.20.1)

A mod that, before reaching the main menu, checks whether any "external"
mods are missing from the `mods/` folder and, if so, shows a mandatory
screen asking for confirmation to download them from Modrinth.

## Client-side only

This mod is client-only. It only acts inside `FMLClientSetupEvent`
(which Forge never fires on a dedicated server) and every screen/GUI
class it touches doesn't even exist in the dedicated server jar. The
constructor also registers a `DisplayTest` extension point telling
Forge's client/server handshake to ignore this mod's presence, so
players won't see an "incompatible mod list" warning when joining a
server that doesn't have it installed (including vanilla servers). You
do not need to add this mod to your server's `mods/` folder.

## How to integrate it into your project

These files are meant to be copied into an existing Forge 1.20.1 project
(MDK / ForgeGradle) that you already have set up, like the ones you
usually work with:

1. Copy the `src/main/java/com/danielhackerxd/modpackdownloader/` folder
   into your `src/main/java/` (or adapt the package to your own
   convention, changing the `package` declaration in every class — this
   is independent of the mod id below).
2. Copy the contents of `META-INF/mods.toml` into your project's
   `mods.toml`, or merge it in if you already have other mods in the
   same jar (you can have several `[[mods]]` blocks in the same
   `mods.toml`). The mod id is `mdfm`.
3. Copy `assets/mdfm/lang/` and `pack.mcmeta` into your
   `src/main/resources/`. If your project already has its own
   `pack.mcmeta`, don't overwrite it — just make sure its `pack_format`
   is 15 (or otherwise compatible with 1.20.1) and add the
   `assets/mdfm/lang/` folder alongside your existing assets.
4. No extra dependencies are needed: it only uses Gson and
   `java.net.http.HttpClient`, which already ship with Minecraft/Forge.
5. Build with your usual `gradlew build`.

## Usage flow

1. **First launch after installing the mod (no `mods.json` yet):** no
   warning is shown. Only `config/mdfm/mods.json` (a sample template) and
   `config/mdfm/README.txt` are generated.
2. **You, as the pack author,** edit `mods.json` with the real mods (see
   the format below) and distribute the modpack with that file already
   filled in.
3. **On the end player's side:** when the game opens, if any of the
   listed files is missing from `mods/`, the warning screen appears
   BEFORE the main menu, blocking the game (Esc does not close it).
   - All checkboxes start checked.
   - If the player unchecks one, the **Next** button becomes disabled
     (greyed out); trying to press it (or unchecking one after Next was
     already active) shows an explanation of why they can't continue.
   - Pressing **Next** with everything checked downloads the mods into
     `mods/`.
   - On success, the player is asked to restart the game (the new jars
     can't be hot-loaded; Forge only loads them on launch).
   - If any download fails, a warning is shown along with a Retry button.
     Downloads also automatically retry a few times with a short backoff
     if Modrinth responds with a rate limit (HTTP 429), before giving up.
4. **Subsequent launches:** as soon as every listed file already exists
   in `mods/`, the screen no longer appears.

## Supported links (automatic resolution)

You don't need to manually dig up the direct CDN link: you can paste a
normal Modrinth version page URL as-is, e.g.
`https://modrinth.com/mod/entityculling/version/MloBcsQQ`. The mod calls
Modrinth's public API (`api.modrinth.com`) to resolve it into the real
CDN download link. No API key or account is needed — Modrinth's public
API has no such requirement.

Any other link (one that's already a direct `.jar` link, e.g.
`cdn.modrinth.com/...`, from your own server, etc) is used as-is, with
no resolution.

If Modrinth changes the structure of their pages or their API in the
future, this automatic resolution could stop working and the regular
expression in `ModDownloader.MODRINTH_VERSION_PAGE` would need to be
updated.

## `config/mdfm/mods.json` format

```json
[
  {
    "name": "Paraglider",
    "url": "https://modrinth.com/mod/paraglider/version/AbCdEfGh",
    "fileName": "paraglider-1.20.1.jar"
  },
  {
    "name": "Entity Culling",
    "url": "https://cdn.modrinth.com/data/XXXXXXXX/versions/YYYYYYYY/entityculling-forge-1.10.5-mc1.20.1.jar",
    "fileName": "entityculling-forge-1.10.5-mc1.20.1.jar"
  }
]
```

- `url` can be a normal Modrinth version page link, or an already-direct
  `.jar` link.
- `fileName` is the name it will be saved as inside `mods/`. It must
  match exactly so the "already installed" check works correctly on
  later launches.

## Important notes

- This mod assumes the links are stable and publicly accessible. If
  Modrinth changes their download URLs over time, you'll need to update
  `mods.json`.
- There is no signature/checksum verification beyond confirming the
  downloaded file starts with a valid ZIP signature (`PK\x03\x04`),
  which only confirms it's *a* valid archive, not that it's the *right*
  one. If you want extra safety, an expected `sha1`/`sha256` field could
  be added to `mods.json` and checked against the downloaded file's hash
  before moving it into `mods/` — let me know if you'd like that added.
- The "already installed" check is based only on the file name in
  `mods/`. If the player deletes or renames the file, it will be asked
  for again.
- This version only supports Modrinth. CurseForge links are not
  recognized or resolved.

##  License & Use

All Rights Reserved
