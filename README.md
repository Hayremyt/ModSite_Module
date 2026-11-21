# ModsSite — web mod-panel module for GravitLauncher 5.7.x

> **Русская версия этого README: [docs/README_RU.md](docs/README_RU.md)**

A LaunchServer module that adds a **web panel for managing profile modpacks**
with downloads and updates from **Modrinth** and **CurseForge**:

- **resource packs and shaders are first-class citizens**: `resourcepacks` /
  `shaderpacks` profile folders, their own modpack entries (`kind: "mod" |
  "resourcepack" | "shader"`), per-type search on Modrinth/CurseForge, and the
  full pipeline — install/updates/optionality/groups/dependencies; a kind
  filter on the "Mods" tab and a type selector in search (default type is
  configurable in Settings → Updates or per browser);
- **suggestion system**: a viewer finds a project in search and presses
  "Suggest"; an admin sees the queue in the "Suggestions" tab (who, what,
  which profile, which kind) with name/user and kind filters — and downloads
  it into the modpack or rejects it with one click; pending-per-user limit and
  a list of logins banned from suggesting are configurable; viewers see their
  own suggestions and remaining limit;
- choosing and configuring **optional mods**, **dependencies** and **conflicts**;
- **optional mods for players**: automatic sync with the profile's
  `updateOptional` — checkboxes in the launcher's "Settings" dialog, with the
  file binding automatically updated when the file name changes (see
  "How it works");
- **updates with file renames** — the old file is tracked by its stored name
  and correctly deleted (including for optional mods);
- **import of existing jars** — files dropped into the mods folder manually are
  matched into the modpack by Modrinth hashes (SHA-1) and CurseForge
  murmur2 fingerprints, without re-downloading; if the hash did not match
  (author rebuilt the jar), the mod is recognized by a fallback search using
  the modId from the jar with SHA-1 verification of version files;
- **"Search" tab with Modrinth/Prism-style filters**: categories, loaders
  (Fabric/Forge/NeoForge/Quilt), game version, sorting (relevance/downloads/
  follows/newest/updated) and pagination;
- **filters, search and sorting on the "Mods" tab**: separate "Optional" and
  "Updates" chips (combinable), a separate sort menu — default / A-Z / Z-A
  (remembered in the browser) — and a live search across INSTALLED mods
  (name/file/id, no API calls);
- **default modpack loader**: a selector in the meta chips of the "Mods" tab
  (Auto/Any/Fabric/Forge/NeoForge/Quilt) — automatically applied to search and
  update checks, overridable per search;
- **update source priority**: Settings → Updates — the Modrinth/CurseForge
  order is changed with ↑↓ buttons; checks follow the order: if the mod is
  not on a source (project deleted, source unavailable, no metadata), the file
  is looked up by hash on the next one, and a file found on a higher-priority
  source re-links the mod to it. "Check updates" also GENERATES metadata for
  "local" mods (SHA-1/murmur2 matching);
- **per-mod "Postpone" button**: a postponed version is not installed —
  neither alone nor via "Update all" — until a newer one is released; a new
  version clears the postponement;
- **"Reload profiles"** — re-reads profile configs (profiles/*.json) from disk
  without a restart: manual edits of `updateOptional`, `modsLoader` etc. take
  effect immediately; mods whose files are bound in `updateOptional` —
  INCLUDING non-standard profile.json forms (`optionalMods[].list` etc., which
  the launcher itself ignores) and entry-name matches — automatically become
  optional when the list is opened; diagnostics via `mods profile optionals`;
- the **"Folder scan"** button — re-reads the content folders and shows where
  they diverged from the modpack list: files deleted manually (restore =
  download again, or remove from the list) and files added manually (adopt by
  Modrinth/CurseForge hashes, or delete from disk); one item at a time or
  the "remaining" bulk buttons;
- **Minecraft color codes in file names** ("Pack &a.zip", "§c" — from
  CurseForge resource pack titles; GravitLauncher chokes on them) are stripped
  on every file-write path, and already present files are renamed
  automatically during "Folder scan" and "Check updates" (the modpack entry's
  file name and the updateOptional binding are updated together with the
  file; "ATM&T.jar" is untouched — "T" is not a color code);
- the **"Update hashes"** button — runs the console command
  `config profileprovider sync`: a full "to be sure" sync — HashedDir of ALL
  updates folders + re-reading the profile list from disk;
- **every wait is visible (1.1.4)**: a thin network activity bar at the top
  edge shows any in-flight API request; skeleton placeholders while a profile
  or a search page loads; spinner + disabled state on every action button
  (add/remove/update/scan/rehash/unban/backup/…); the update progress window
  got a live elapsed timer (mm:ss); toasts are closable and linger longer
  for errors; admin tables show a "Loading…" row instead of looking empty;
- **inline confirmations (1.1.4)**: update/remove/bulk actions ask in a
  styled dialog (Esc or clicking the backdrop = "Cancel") instead of the
  browser's native `confirm()` — same look on every platform, focus-trapped;
- **polish (1.1.4)**: cards cascade in with a small stagger after load, the
  active tab gets an animated underline, counters use tabular numerals,
  single-page search hides the pager; fixed: theme flash + CSP console error
  on every page load (the pre-paint theme script now lives in the CSP-clean
  dynamic theme.js), unreadable "update available" badge in the comic/pixel
  dark themes, language switch no longer stops translating buttons that were
  busy at least once, and a crash of the backups poller when a backup job
  finished.
- **hybrid authorization**: local accounts from the config + launcher accounts
  (admin detection via launcher permissions or your own SQL query, e.g. an
  `isAdmin` column);
- **brute-force protection**: IP bans with escalating durations, login bans,
  whitelist/blacklist (console commands + panel), rate limiting, TOTP 2FA, audit;
- works over both **HTTP and HTTPS** (the cookie `Secure` flag is automatic);
- interface: **Russian/English**, no CDN and no external resources;
- **six built-in themes — each with a light and a dark variant** — plus
  admin-defined custom themes (see "Themes"); the design default is
  glassmorphism (dark tech + status green, glowing background blobs,
  translucent glass cards, SVG icons without emoji, responsive layout, focus
  rings, `prefers-reduced-motion` support).

Tested on **LaunchServer v5.7.13** (live run: login, TOTP, brute-force bans,
search/add/update of mods, catalog with filters, filters/search/sorting and
"Postpone", settings and source priority, modpack loader, optional adoption
from updateOptional — including non-standard profile.json forms
(`optionalMods[].list`), profile reload, file renames, import of existing
jars, hash recalculation, content kinds and the suggestion system, themes
with light/dark variants and custom theme management, console log levels;
1.1.4 additions — styled confirmations, a real update job with the progress
window and elapsed timer, folder scan, `config profileprovider sync` rehash,
backups tab, RU/EN switch (clean browser console under the server CSP);
automated checks: 430 smoke, 27+ live API/browser checks — all green).

---

## Contents

1. [Requirements](#requirements)
2. [Building](#building)
3. [Installation](#installation)
4. [Quick start](#quick-start)
5. [Interface and themes](#interface-and-themes)
6. [How it works](#how-it-works)
7. [Authorization](#authorization)
8. [Security](#security)
9. [HTTP and HTTPS](#http-and-https)
10. [Console commands](#console-commands)
11. [Configuration (all options)](#configuration)
12. [Backups](#backups)
13. [Web API](#web-api)

---

## Requirements

| Component | Version |
|---|---|
| LaunchServer (GravitLauncher) | **5.7.x** (tested on 5.7.13) |
| Java (build and run) | **21+** |
| Internet from the server | to `api.modrinth.com` (and `api.curseforge.com` if used) |

## Building

The single build option — the Gradle Wrapper with the path to your
LaunchServer build:

```bash
cd ModsSite_module
./gradlew build -PlaunchserverDir=/path/to/LaunchServer      # Linux/macOS
gradlew.bat build -PlaunchserverDir=C:\path\to\LaunchServer  # Windows
```

No need to install Gradle — `gradlew` downloads Gradle 8.10.2 itself, and if
a suitable JDK 21 is missing it downloads that too (foojay). The path points
to an UNPACKED `LaunchServerBuild.zip` from the
[GravitLauncher release](https://github.com/GravitLauncher/Launcher/releases)
— its `lib` subdirectory is required. Instead of passing it every time, set
it once in `gradle.properties` (`launchserverDir=...`).

> Note: the toolchain is Java 21, same as LaunchServer 5.7.x itself.
> LaunchServer jars are built with bytecode v65, which older javac versions
> cannot read — this was one of the typical "gradle build fails" causes.

The ready jar: `build/libs/ModsSite_module.jar`.

The distribution zip already contains a **pre-built and tested**
`ModsSite_module.jar` (on 5.7.13) — if you are not changing the source code,
you can skip the build entirely.

### GitHub Actions: build by button and releases

`.github/workflows/build.yml` ships with the sources — pushing the repo to
GitHub is the only setup needed:

1. Open the **Actions** tab -> **Build & Release** -> **Run workflow**.
2. Leave `release_tag` empty for a build-only run — `ModsSite_module.jar` and
   the distribution zip appear in the run's **Artifacts**.
3. Set `release_tag` to `v1.1.1` to also publish a GitHub Release with both
   files attached; the tag is created automatically if it does not exist yet.
4. Pushing a `v*` tag does the same automatically, no button needed.

Where does CI get the LaunchServer jars? It downloads `LaunchServerBuild.zip`
from the GravitLauncher releases
(`https://github.com/GravitLauncher/Launcher/releases/latest/download/LaunchServerBuild.zip`,
cached per release tag; a specific release can be pinned via the `ls_release`
input) and unpacks it next to the project — the same `lib` directory the local
build expects, so LaunchServer does not have to be committed to the repo.

Two guards are built in: the release tag must match the module version
(`v1.1.1` <-> `version = "1.1.1"` in `build.gradle.kts`, otherwise the run
fails with a hint), and every run verifies the produced jar (main class,
`Module-Main-Class` manifest attribute, the CurseForge `totalCount` parsing
present in the bytecode) and prints its SHA-256 into the log.

## Installation

1. Copy `ModsSite_module.jar` (the pre-built one from the distribution zip,
   or `build/libs/ModsSite_module.jar` of your own build) into the LaunchServer
   `modules/` folder.
2. Add the module to `modules.json` (in the LaunchServer root):

```json
{
  "loadModules": ["ModsSite_module"],
  "loadLauncherModules": []
}
```

(if you already have a `loadModules` list — just append `"ModsSite_module"`)

3. Start LaunchServer. On first start the module:
   - creates `config/ModsSite/Config.json` with default settings;
   - creates a **local `admin` account with a random password** — the password
     is printed once to the console and the log:
     `FIRST START — web login created (shown ONCE): login: admin, password: XXXX`;
   - opens the site at the `/mods` path.

4. Change the password immediately: `mods user pass admin NewPassword`
   (or in the config).

The site: `http://<launcher-address>:9274/mods/`. If the server has a domain
(e.g. `mods.example.com`), the site can also open in the domain root —
`http://mods.example.com/` — and even in both addresses at the same time:
add the domain to `web.siteDomains` (Settings tab → "Site domains") — no
restart needed (see "HTTP and HTTPS" → "One site, two addresses").

#### Web UI files in the config (config/ModsSite/web)

The UI files (`index.html`, `style.css`, `app.js`, `i18n.js`, `favicon.svg`)
are stored NOT in the jar but in `config/ModsSite/web/` — at startup the
module writes them there automatically and serves them from disk. To change
the appearance, simply edit the file and reload the page (`Ctrl+F5`): no
restart needed.

The module keeps the set consistent at every start:
- a file is missing (deleted/lost) — restored from the jar;
- a file was not modified by the admin and the module version grew — updated
  to the new one;
- a file was modified by the admin — the edit is kept, the file is untouched;
- a file is missing everywhere (neither on disk nor in the jar) — an ERROR is
  logged with the file names.

To force-overwrite EVERYTHING from the jar (local edits will be lost):
`mods web restore`. File status: `mods web`.

The emergency case "jar without web/" is healed by the same mechanism: unpack
`web/*` from the distribution zip into `config/ModsSite/web/` — the UI comes
alive without replacing the jar.

## Quick start

1. Log in (`admin` + the password from the log; if TOTP is enabled — the code
   from the authenticator app).
2. At the top — LaunchServer profile tabs (modpack = profile).
3. Find mods: the "Search" tab — pick a source (Modrinth/CurseForge), type a
   name, press "Search", add what you need (filters are the "Optional"/
   "Updates" chips; installed-search and sorting live on the "Mods" tab; the
   loader and game version are pre-filled from the profile).
   - Dependencies are linked automatically; missing ones will be suggested.
4. The "Optional" checkbox on a card makes a mod optional (a player will be
   able to disable it in their launcher) or required for everyone. Enable/
   disable toggles are gone — downloaded means needed: the whole modpack is
   the composition. Conflicting mods coexist peacefully; the launcher simply
   won't let a player enable both at once (the site only warns and highlights).
5. **"Folder scan"** — compares the content folders with the modpack list:
   files deleted manually ("Restore" = download again, or "Remove from
   list") and files added manually ("Add to pack" by hashes, or "Delete
   file"). Each item one by one, the rest — via the bulk "remaining"
   buttons; while an update job runs, the scan buttons are disabled.
6. **"Check updates"** — refreshes mod info AND checks sources for newer
   versions (don't want a specific version? use the per-mod **"Postpone"**
   button). **"Update all"** updates ONLY what the check found (plus mods
   whose file is missing) — it never re-downloads already-actual mods.
   While an update runs, both buttons just open the progress window.
7. **Progress window** shows per-mod status and bytes, a live elapsed timer
   (mm:ss — you can tell a long download from a hang), and has a **"Stop"**
   button: downloads already started are finished, queued ones are cancelled.
   If you reload or close the page mid-update, the window reopens by itself
   (the running job survives on the server).
8. **"Update hashes"** — runs the `config profileprovider sync` command (a
   full sync: hashes of all updates folders + re-reading the profiles), after
   which launcher clients sync files on next launch (download new ones and
   **delete stale files**, including old mod versions).
9. The update-check order (Modrinth/CurseForge), the number of **parallel
   downloads** and the **source response timeout** live on the **"Settings"**
   tab; if you edited profiles/*.json by hand — the **"Reload profiles"**
   button.

## Interface and themes

### Themes

Every theme ships in **two variants — light and dark**. The palette switch
(sun/moon) sits right in the theme menu; the choice of theme and variant is
stored in the browser (`localStorage`), applied before first paint (no flash
of the wrong theme), and survives reloads. Demo links work too:
`?theme=terminal&mode=light`.

Six built-in themes:

| Theme | Look |
|---|---|
| **Glass** (default) | dark glass, green accent, glowing background blobs; light variant — airy white-blue glass |
| **Comic** | halftone paper, bold black outlines, flat print shadows; dark variant — "night issue" with cream ink |
| **Simple** | light minimalism, no effects; dark variant — strict dark gray |
| **Neon** | dark cyberpunk with cyan-pink glow; light variant — "day synthwave" white with neon |
| **Pixel** | Minecraft flavor: checkerboard background, zero radii, hard blocky shadows, monospace; light variant — 8-bit morning |
| **Terminal** | green CRT terminal with scanlines and a blinking cursor; light variant — "paper terminal" |

Custom (admin-defined) themes are added the same way and also have a light
and a dark variant — see [Making your own themes](#making-your-own-themes).

Theme management (admin only, Settings → **Appearance**):

- **Default theme** and **default variant** — what users without a personal
  choice see (a personal choice always wins);
- **Built-in themes** on/off — a disabled theme is hidden from EVERYONE:
  players, viewers and admins alike (at least one theme must remain
  available);
- **Custom themes** — add and delete your own: a name, a font (regular/
  monospace), corner style (sharp/rounded/pill) and seven colors per variant
  (background, cards, text, muted text, borders, accent, second accent) —
  every other tone of the interface is derived automatically. The add form
  has a **live preview**: the whole site recolors as you pick colors. Up to 16
  custom themes; they are available to everyone exactly like built-ins.

Who can do what:

- **player / viewer** — picks any of the available themes and the light/dark
  variant; the choice is personal and stored in their browser;
- **admin** — the same, plus managing: default theme/variant, disabling
  built-ins, adding/deleting custom themes.

The theme configuration is served to the browser by the module itself as a
tiny dynamic `/theme.js` (available before login, never cached), so any admin
change reaches every visitor on their next page load.

The layout is responsive — from 390px phones to wide desktops (full-width
fields and buttons, nothing overflows), all icons are inline SVG, and the
interface language (Russian/English) switches in one click.

### Making your own themes

Three levels — from "no code at all" to a fully hand-made design.

#### 1. In the panel — from base colors, no code

Settings → **Appearance** → **"Add theme"**:

1. type a **name** (up to 40 chars) — the theme id is generated from it
   automatically;
2. pick a **font** (regular / monospace) and the **corner style**
   (sharp / soft / round);
3. set **seven colors of the light variant**: background, cards, text, muted
   text, borders, accent, second accent;
4. set **seven colors of the dark variant** — the same keys.

While you pick the colors, the whole site recolors **live** (preview), and
the sun/moon switch shows both variants instantly. "Add" puts the theme
into the list; **"Save settings"** sends it to the server — after that it
appears in the theme menu of EVERY visitor, just like a built-in. Up to 16
custom themes; deleting — the trash icon in the list + "Save settings".

Every other tone of the interface (hover tints, shadows, badges, toasts,
focus rings) is **derived automatically** from those seven colors — no
palette-design skills needed.

The same list can be edited by hand in `config/ModsSite/Config.json` →
`themes.custom` (an example is in "Configuration"); the server validates
ids, colors (`#rrggbb`) and the 16-theme limit itself.

#### 2. Fully custom design — your own backgrounds and effects

The web UI is plain editable files on disk: `config/ModsSite/web/`
(`index.html`, `style.css`, `app.js`, `i18n.js`). Edit a file — reload the
page (`Ctrl+F5`); no server restart is needed, and files modified by hand
are **never overwritten** by the module (status: `mods web`; reset
everything to stock: `mods web restore`).

A theme added via the panel gets the page attribute
`data-theme="c-<id>"` (the `c-` prefix marks custom themes). So the recipe
for a completely custom look is:

1. **Add the base via the panel** (way 1) — name it, say, "Night Cherry";
   its id becomes `c-night-cherry`.
2. **Append the decorations to `config/ModsSite/web/style.css`** — what the
   panel form cannot express: background gradients, pattern backdrops,
   glows, shadows, animations:

```css
/* background and effects for the custom theme "Night Cherry" */
[data-theme="c-night-cherry"] {
    --decor-hi: #2A1020;              /* top end of the page gradient */
    --decor-lo: #120611;              /* bottom end */
    --blob-a: #FF5C8A;                /* colors of the three glowing blobs */
    --blob-b: #B197FC;
    --blob-c: #5C6BC0;
    --shadow-card: 0 8px 32px rgba(120, 20, 60, 0.35);
    --focus-ring: 0 0 0 3px rgba(255, 92, 138, 0.18);
}

/* a checkerboard backdrop instead of the default grid — like the Pixel theme */
[data-theme="c-night-cherry"] .bg-blob { display: none; }
[data-theme="c-night-cherry"] .bg-grid {
    background-image:
        linear-gradient(45deg, rgba(255, 255, 255, 0.03) 25%, transparent 25%),
        linear-gradient(-45deg, rgba(255, 255, 255, 0.03) 25%, transparent 25%);
    background-size: 42px 42px;
}
```

3. Reload the page. Whoever picks "Night Cherry" (light or dark variant)
   gets your design — while the palette itself keeps coming from the panel,
   so recoloring later does not require touching the CSS again.

Tokens useful for decorations: `--decor-hi` / `--decor-lo` (the page
gradient), `--blob-a` / `--blob-b` / `--blob-c` (the three glowing blobs),
`--shadow-card`, `--shadow-pop`, `--blur`, `--t` / `--t-fast` (animation
timing). The `bg-blob` / `bg-grid` layers can be restyled or hidden
(`display: none`) — that is exactly how the built-ins differ from each
other; peek at the `[data-theme="comic"]`, `[data-theme="pixel"]` etc.
blocks in `style.css` for ready-made examples.

Notes:

- backgrounds are built from **pure CSS** (gradients, patterns): the module
  serves only the five UI files, and the security headers (CSP) forbid
  external resources — no CDNs, no image files;
- the light/dark pair works automatically: the light tokens live in the base
  `[data-theme="c-..."]` block, dark overrides — in
  `[data-theme="c-..."][data-mode="dark"]` (see the built-in blocks in
  `style.css`);
- a demo link for quick checks: `?theme=c-night-cherry&mode=dark`.

#### 3. A hardcoded theme — like a built-in

Want the theme to exist without the panel (e.g., to ship with your fork)?
Add a full token block `[data-theme="mytheme"] { ... }` to `style.css`, an
entry to `BUILTIN_THEMES` in `app.js` —
`{ id: "mytheme", key: "theme.mytheme", light: "#...", dark: "#...", accent: "#..." }`
(the colors are used for the menu swatch and the browser chrome) — and the
name pair `"theme.mytheme"` in `i18n.js` (ru/en). That is exactly how the six
built-ins are made.

### "Mods" tab: filters, sorting, "Postpone"

Above the mod list — a control row in the Modrinth spirit:

- **"Optional"** and **"Updates"** — independent chip buttons with counters
  (both can be on — the intersection is shown); the state is remembered in
  the browser;
- **"Search installed"** — a live list filter (name/file/id, no API calls)
  with a clear button; works together with the chips and the sorting;
- **Sorting** — a separate dropdown: Default / A-Z / Z-A;
- in the meta chips above the list — the **modpack loader selector**
  (Auto / Any / Fabric / Forge / NeoForge / Quilt): the saved loader is
  automatically applied to search and update checks;
- below the filters — a "Showing: N of M" counter;
- a mod with an available update has a **"Postpone"** button (clock icon):
  the latest version is marked postponed — a "postponed" badge appears, and
  the update is not installed — neither alone nor via "Update all". The
  button turns into "Unpostpone"; a newer version at the source clears the
  postponement automatically;
- **"Reload profiles"** — re-reads profile configs (profiles/*.json) from
  disk: if you edited `updateOptional`, `modsLoader`, `modsGameVersion` etc.
  by hand, the site picks it up without a server restart (after the button,
  the profile list and modpack reload; mods whose files are bound in
  `updateOptional` become optional).

### "Settings" tab (admin)

The module settings section with three subtabs:

- **"Updates"**:
  - **Update source priority** — an ordered Modrinth/CurseForge list with ↑↓
    buttons; an unavailable source (no API key) is marked. The order applies
    to update checks and to import/metadata generation alike;
  - **"Modpack behavior"** — toggles: auto hash recalculation, SHA hash
    verification after download, allow download without a hash, auto
    dependencies, sync with `updateOptional`, "optional mods enabled by
    default"; dropdowns for the default update channel and the API cache TTL
    in minutes;
  - **"Ignored files by category"** — .gitignore-style patterns (one per
    line) separately for mods, resource packs and shaders: matching files and
    folders are never shown as "extra", never imported and never queried
    against Modrinth/CurseForge. Syntax: `*` / `?` / `[a-z]` globs, `**` for
    any nesting, a `/` in the middle or at the start anchors to the folder
    root, a trailing `/` matches directories only, `!pattern` un-ignores
    (the last matching rule wins), `#` is a comment. For mods, `.index` is
    ignored by default — the service folder of Sinytra Connector
    (Fabric mods on Forge);
  - **"Server console output"** — the module's log level: `off` (completely
    silent), `error` (errors only), `warn` (errors and warnings — default),
    `all` (everything including info/debug). Applies immediately, affects
    only the module's own messages and never touches the audit file;
  - saving — one button; changes are written to `Config.json` right away.
- **"Appearance"** — theme management (see "Themes" above).
- **"Security"** — the former tab in full: bans, IP filter, audit, local
  accounts.

### "Search" tab (catalog)

A separate tab for discovery — like the mod browser in Prism Launcher:
- the **search bar** with a source selector (Modrinth/CurseForge); an empty
  query means catalog mode (browsing popular mods);
- **Loader**: chips "Any / Fabric / Forge / NeoForge / Quilt";
- **Game version**: a field with suggestions for popular versions; the
  default is the selected profile's version (as in Prism);
- **Sorting**: relevance / downloads / follows / newest / updated (for
  CurseForge "follows" approximately maps to popularity — CF does not expose
  it);
- **Categories**: a live list from the source API (Modrinth `/tag/category`,
  CurseForge `/v1/categories`); multiple choices are OR-ed;
- **pagination**: 20 cards per page, a "Found: N" counter;
- each card shows the source, up to 3 categories, downloads/follows/date and
  an **"Add"** button (admin) or an "already in modpack" tag.

CurseForge specifics (the Core API is stricter than the website search):
- the total comes from `pagination.totalCount` — the counter and pagination
  work exactly like on curseforge.com;
- the loader filter is only sent together with a game version (per the spec,
  a loader without a version returns exactly 0 results);
- if a strict request returns nothing, the module automatically retries with
  a softer one and explains itself with a yellow notice under the filters:
  first without the loader, then without the game version (the website does
  not force a version either). Categories are never dropped.

Accessibility and details: keyboard focus rings (`:focus-visible`), Esc
closes modals, focus never leaves an open modal, icon buttons have
`aria-label`s, animations are disabled under `prefers-reduced-motion`, toasts
and error blocks are announced as live regions for screen readers.

## How it works

- **Modpack = ClientProfile.** Mod files land in
  `updates/<profile>/mods/` (subdirectory is configurable), the state lives
  in `config/ModsSite/modpacks/<profile>.json`.
- **Game version** comes from the profile; the **loader**
  (fabric/forge/neoforge/quilt) — since 1.0.12 — is chosen by the selector on
  the "Mods" tab (stored in `modpacks/<profile>.json` → `loader`), or from
  profile properties (`modsLoader`, `loader`), or guessed from the profile
  name.
- **Optional mods**: disabling a mod at the modpack level is gone —
  "downloaded means needed", the whole modpack is the composition. Files are
  deleted only when a mod is removed from the modpack; an optional mod can be
  switched off by the PLAYER locally — the launcher won't download it
  (exclusion via `OptionalActionFile.files`).
- **Updates with file renames**: every mod stores `installedFileName`. On
  update the new file is downloaded to a temp file (with SHA-1/SHA-512
  verification from the Modrinth/CurseForge API), then **atomically moved
  into place and only afterwards is the old file deleted by its stored
  name** — renames never leave garbage behind, and a failed download/verify
  or a disk error leaves the old working mod untouched (1.1.1: the file is
  replaced only after the new one is fully in place; the `.part` temp file
  is always cleaned up).
- **Hashes**: after any change (and via the button),
  `LocalProfilesProvider.sync(...)` is invoked — the same mechanism the
  `sync` command uses.
- **Dependencies/conflicts** come from the source APIs (Modrinth
  `dependency_type`, CurseForge `relationType`) and are linked to modpack
  mods by project ID.
- **Source priority and re-linking** (1.0.11). Update checks follow
  `mods.updateSourcePriority`: a mod's own source is tried in its place in
  the order, and on higher-priority sources the installed file is looked up
  by hashes (Modrinth — batch by SHA-1 `POST /v2/version_files`, CurseForge —
  batch by murmur2 `POST /v1/fingerprints/432`). A file found on a
  higher-priority source re-links the entry (source/projectId/name/icon, the
  installed file's version) — that is why "extra" CurseForge mods smoothly
  migrate to Modrinth, and mods dropped into the folder manually get their
  metadata via "Check updates". If the owning source is down or the project
  deleted, the same fallback to the next source in order kicks in.
- **"Postpone"** (1.0.11): the entry stores `skippedVersionId` — while that
  version is the latest at the source, it does not count as an update and is
  not installed (including "Update all"); a newly released version
  automatically returns the mod to the update list.

### Optional mods: the admin's choice and the player's choice

There are **two different optional-mod systems**, and the module works with
both:

1. **Admin level (module)** — the "Optional" checkbox on a card or in the
   entry settings: defines whether a player can disable the mod. The
   composition does not change: enable/disable toggles are gone, downloaded
   means needed.
2. **Player level (the vanilla `updateOptional` system)** — checkboxes in the
   launcher's "Settings" dialog: the server stores all files, the client
   skips the ones the player disabled (exclusion via `OptionalActionFile.files`).

The `mods.syncProfileOptional` option (enabled by default) bridges them: for
every installed optional mod the module maintains an entry in the profile's
`updateOptional`:

```jsonc
"updateOptional": [
  {
    "name": "sodium",                        // = the mod's id (slug), stable
    "info": "Chunk rendering optimization",  // note/description from the site
    "mark": true,                            // enabled for players by default
    "visible": true,
    "actions": [ { "files": { "mods/sodium-fabric-0.6.0.jar": "" }, "type": "file" } ],
    "dependenciesFile": [ { "name": "indium" } ]
  }
]
```

- **The file binding updates automatically**: in 5.7.x the paths in
  `actions.files` are exact (no globs), so when an update renames the file
  the module rewrites the path in the same transaction — before the rehash.
  No manual edits needed.
- **The player's choice survives updates**: the client stores the choice by
  `name`, not by file name, so `name` is fixed when the entry is created and
  never changes afterwards (remembered in the mod's `profileOptionalName`).
- `dependenciesFile`/`conflictFile` are filled from modpack relations — but
  only with references to mods that have their own entry (otherwise the
  client would get a null in the dependency graph).
- The entry is removed when the mod becomes required or is deleted from the
  modpack.

#### How a new entry's name is chosen

If the mod is not yet in the profile config, the name is picked
automatically (in priority order):

1. **the identifier from the service it was downloaded from** — the
   Modrinth/CurseForge slug (= the mod's `id` in the modpack); stable across
   versions and requires no jar parsing;
2. **the modId from the jar itself** — if the service gave no slug (ids like
   `curseforge-12345`): read from `fabric.mod.json` → `quilt.mod.json` →
   `META-INF/mods.toml` (and `neoforge.mods.toml`) → `mcmod.info`;
3. edge cases — a synthetic id, a human-readable name from jar metadata or
   the file name.

The chosen source is logged (`name from service slug` / `jar modId` / ...).
The jar modId is stored in the modpack (`installedModId`), and the mod name
from jar metadata (`installedModName`) is used as a fallback for the entry's
`info`.

#### Adopting the admin's manual entries

If the profile already has an entry with a different `name` bound to our
mod's file (the current one OR **past** file names — the history is kept in
`previousFileNames`), the module does not duplicate it — it **adopts** it:

- its binding is re-linked on updates (otherwise it would go stale — the
  same exact-path problem);
- `name`, `info`, `mark`, `visible` and other admin settings are left
  untouched — so player choices stored by name are not lost;
- when the mod is disabled or removed, only its paths are cleaned from the
  entry; if no file bindings remain and there are no other actions (jvmArgs
  etc.), the entry is removed entirely, otherwise it survives with the
  remaining files;
- other mods' dependencies reference the actual entry name (including an
  adopted one), not the modpack id.

**Ownership rules**: an entry belongs to the module if its `name` matches the
mod's `profileOptionalName`/`id` from the modpack — such entries are managed
wholesale (except manual move-bindings with a non-empty value — those are
kept). The `mark`/`visible`/`info` fields of an existing entry are never
overwritten (set once at creation). Don't want player checkboxes? Set
`mods.syncProfileOptional: false`.

#### Dependencies and conflicts: the relation editor in entry settings

In the entry settings (the "Settings" button on any entry; a required
resource pack/shader is turned optional by the "Optional" checkbox) — the
"Dependencies and conflicts" block:

- **targets are only optional mods** of this modpack (required ones are
  always on, referencing them is pointless; members of the same optional
  group are hidden too — a group shares one switch);
- each candidate has two checkboxes: **"Dependency"** and **"Conflict"**;
  a checked box creates the link, **both unchecked delete it** (it used to be
  an awkward multi-select). Dependency and conflict on the same pair are
  mutually exclusive;
- conflicts are **symmetric**: unchecking a conflict on one mod removes it
  on both;
- **purged links stay purged**: a link deleted by the admin gets a
  "tombstone" — auto-import from the API ("Check updates", "Resolve
  dependencies") never re-adds it; re-checking the box clears the tombstone
  and auto-import works again. The same applies to profile relations: a list
  purged on the site is never re-filled from the profile (filling happens
  only once, into an empty list);
- the lists are written to the profile (`dependenciesFile`/`conflictFile` of
  `updateOptional` entries) on every settings save — the launcher applies
  them when the player toggles checkboxes.

## Authorization

The `auth.mode` setting:

| Mode | Local accounts (config) | Launcher accounts |
|---|---|---|
| `hybrid` (default) | yes | yes |
| `local` | yes | no |
| `launcher` | no | yes |

**Local accounts** — the `accounts` list in the config. A password can be
written in plain text into the `password` field — at startup it is
**automatically replaced with a PBKDF2 hash**. Management: the
`mods user ...` command.

**Launcher accounts** are verified through the auth provider (`authId`,
empty = the default pair). Which of them is an admin is decided by
`launcherAuth.adminCheck.type`:

- `permissions` (default) — admin if the account has the `mods.admin`
  permission or the `modsadmin` role (configurable: `permission`, `role`).
  In GravitLauncher MySQL/PostgreSQL providers this is the `permissions`/
  `roles` column of the users table (e.g. `"perms": ["mods.admin"]`).
- `sql` — **your own SQL query**, e.g. against an `isAdmin` column (any
  column name — it is just part of your query):

```json
"launcherAuth": {
  "authId": "",
  "adminCheck": {
    "type": "sql",
    "sql": {
      "url": "jdbc:mysql://127.0.0.1:3306/launcher?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
      "user": "launcher",
      "password": "database password",
      "query": "SELECT isAdmin FROM users WHERE username = ?",
      "params": ["{login}"],
      "trueValues": ["1", "true", "t", "yes", "y", "admin"]
    }
  },
  "treatNonAdminAs": "viewer"
}
```

`url` is a regular JDBC URL. For MySQL:
`jdbc:mysql://host:3306/database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`.
The MySQL driver (`mysql-connector-j`) ships with LaunchServer in `lib/` —
nothing extra to install. Leave `url` empty to reuse the connection settings
of the launcher's SQL provider (then `user`/`password` are not needed either).

Substitutions in `params`: `{login}`, `{uuid}`, `{username}`. The first
column of the first row is compared to `trueValues` (case-insensitive).

- `treatNonAdminAs`: `viewer` (read-only) or `deny` (login refused).
- `auth.onlyAdminsCanLogin: true` — the site admits **admins only** (local
  non-admins and regular launcher accounts are refused).
- **TOTP 2FA**: for local accounts — `mods user totp <login>` (the secret and
  an otpauth:// link are printed to the console). For launcher accounts — the
  `launcherAuth.extraTotp` map: `{"login": "base32-secret"}`.

### Live roles and superadmin

Roles are **re-evaluated on every request** instead of being frozen at login:

- granted an admin in the launcher DB (or in permissions) — reload the page —
  they are an admin already; revoked — the rights disappear on the very next
  request (no logout needed);
- changed a role in the `accounts` config section — picked up immediately:
  the module config file is re-read automatically whenever it changes on disk
  (accounts, roles, superadmins, domains — no `mods reload`, no restart);
- removed/disabled a local account — admin rights are dropped immediately.

**Superadmin** — logins are set ONLY manually in the module config file:

```json
"superAdmins": ["your_login"]
```

It works no matter where the account comes from — the launcher DB or a local
record. A superadmin has all admin rights and is the only one who sees the
**Security → Roles** sub-tab, where any admin can be marked as a **viewer**:

- the mark is stored in a separate file `config/ModsSite/admin-roles.json`;
- **the admin record in the config or launcher DB is not modified** — the
  override is removed just as easily (the "Restore role" button);
- a superadmin cannot be demoted (`superadmin_protected`);
- role changes apply without logout — on the user's next request; the audit
  log records `admin_role_override` and `superadmin_denied`.

## Security

Everything can be toggled independently in the config.

| Mechanism | Setting | Behavior |
|---|---|---|
| IP brute-force ban | `security` | N failures within the window → ban; **the duration escalates** per `banScheduleMinutes` (5 min → 15 min → 1 h → 6 h → 24 h); after a ban expires, a repeat offense moves to the next level (reset after `levelDecayHours` of clean time) |
| Login ban | `security.accountLock` | M failures for a login (from any IP) → escalating lockout |
| Whitelist | `whitelist` + `mods whitelist add <ip\|cidr>` | IPs/CIDRs that are **never banned** (and skip rate limits) |
| Blacklist | `blacklist` + `mods blacklist add <ip\|cidr>` | IPs/CIDRs that are **always refused** (403) |
| Rate limit | `rateLimit` | Separate limits: general API (240/min) and login (30/min) per IP |
| TOTP 2FA | `auth.totpEnabled` | For accounts with a secret |
| CSRF | — (always on for POST) | Each session has its own `X-Mods-CSRF`; a POST without it — 403 |
| Cookies | `web.cookieSecure/cookieSameSite` | HttpOnly, SameSite=Lax, Secure — automatic on HTTPS |
| Security headers | `web.securityHeaders` | CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy |
| Audit | `audit` | JSONL journal: logins/failures/bans/composition edits/IP filter; size-based rotation |
| Passwords | — | PBKDF2-SHA256 (210k iterations), constant-time comparison |

Protection state is stored in `config/ModsSite/ipfilter.json` (survives
restarts); sessions in `config/ModsSite/sessions.json` (only SHA-256 token
hashes on disk).

Unban: `mods unban <ip>`, `mods unban account <login>` or the buttons in the
"Security" panel.

## HTTP and HTTPS

In GravitLauncher 5.7.x the LaunchServer itself listens on **HTTP**
(configured in `LaunchServer.json` → `netty.binds`); TLS is usually
terminated by a reverse proxy. The module fully supports both:

- all interface links are **relative** — works on any host/port/protocol;
- the cookie `Secure` flag: `web.cookieSecure = auto` (based on the actual
  HTTPS of the connection) / `always` / `never`;
- `web.trustProxyHeaders = auto` — trusts `X-Forwarded-For`/
  `X-Forwarded-Proto` if LaunchServer has `netty.ipForwarding` enabled (can
  be forced `true`/`false`);
- `web.httpsRedirectUrl` — an optional HTTP→HTTPS redirect (for proxies).

An nginx example (proxies both the site and the launcher API):

```nginx
server {
    listen 443 ssl http2;
    server_name launcher.example.com;
    # ssl_certificate ...; ssl_certificate_key ...;

    location / {
        proxy_pass http://127.0.0.1:9274;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;      # launcher websocket
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

and in `LaunchServer.json` enable:

```json
"netty": { "ipForwarding": true, ... }
```

— then the module sees real client IPs behind the proxy.

### One site, two addresses: ip/mods/ and a domain root

By default the site lives at the `/mods` path — e.g.
`http://192.0.2.10:9274/mods/`. If a domain points straight at the
LaunchServer (an A record to the server IP, ports 80/443 via a proxy or a
plain port), it can be added to `web.siteDomains` — and the site opens IN
THE ROOT of that domain:

- `http://192.0.2.10:9274/mods/` — keeps working as before;
- `http://mods.example.com/` — the same site in the domain root;
- both addresses work simultaneously and lead to the same site;
- several domains can be listed (up to 16, via the site settings or the
  config: one per line or comma-separated);
- the port in the Host header is ignored: write the domain without a port;
- any form of the domain is accepted — `mods.example.com`,
  `https://mods.example.com/`, `mods.example.com:443` all normalize to the
  same host;
- **subdomains via wildcard**: an entry `*.example.com` matches ANY subdomain
  of `example.com` at any depth (`mods.example.com`, `a.b.example.com`). The
  bare `example.com` is NOT included — add it as a separate line if the site
  should also open at the apex. A `*` anywhere else (e.g. `a.*.com`) is
  rejected by the sanitizer.
- matching is exact otherwise: `mods.example.com` does NOT match
  `example.com` or `other.mods.example.com` — list each domain you want.

If the site does not open in the domain root, check two common causes:

1. **A reverse proxy (nginx, Caddy, Cloudflare) replaces the Host header.**
   By default `proxy_pass` sends the proxied address as Host, so the module
   sees `Host: 127.0.0.1:9274` instead of your domain and does not match it
   (this is why it may work on localhost but not on the real domain). Fix:

   ```nginx
   location / {
       proxy_pass http://127.0.0.1:9274;
       proxy_set_header Host $host;
       proxy_set_header X-Forwarded-Host $host;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
   }
   ```

   The module also checks `X-Forwarded-Host` (first value) when proxy headers
   are trusted (`web.trustProxyHeaders` = `true`/`auto` + netty `ipForwarding`),
   so either header is enough.

2. **The domain is simply missing from `web.siteDomains`.** The module logs a
   WARN (max once per minute per host) whenever a site-like path (`/`, static
   files) arrives with a Host that is not in the list — the log names the
   exact Host it received AND the currently configured domains, so the
   missing entry is obvious. For subdomains, either add the exact subdomain
   or a wildcard `*.example.com`.

Important: only the listed domains are served in the root. Root requests of
any other host (e.g. `http://192.0.2.10:9274/` from the launcher — profile
and file downloads) are passed down the LaunchServer pipeline untouched —
the file server and launcher websocket work exactly as before.

Technical detail: the `/api` path on the server is taken by the launcher
websocket, so the site's API is always available at `/mods/api/...` (on any
host, including a root domain); the UI detects the working API base at
startup with a probe `health` request — no configuration needed.

Config example:

```jsonc
"web": {
    ...
    "siteDomains": ["mods.example.com", "*.example.net"]
}
```

After editing the config — `mods reload`; via the site settings it applies
immediately.

## Console commands

All commands are registered in the LaunchServer console under the root
command `mods`:

```
mods status                              — module status
mods user add <login> <password> (admin|viewer)
mods user remove <login>
mods user list                           — local accounts
mods user pass <login> <password>        — change password (kills sessions)
mods user role <login> (admin|viewer)
mods user enable|disable <login>
mods user totp <login>                   — enable 2FA (prints secret/link)
mods user totpoff <login>
mods whitelist add|remove|list [ip|cidr] — never ban
mods blacklist add|remove|list [ip|cidr] — always refuse
mods bans                                — active IP bans and login locks
mods unban <ip> | account <login>
mods rehash [profile|all]                — recalculate hashes (site button)
mods check [profile]                     — check for mod updates
mods reload                              — reload the module config
mods site                                — site status (on/off)
mods site on                             — enable the site (aliases: enable)
mods site off                            — DISABLE the site: all /mods requests
                                           (pages and API) answer 503 "Site closed",
                                           all web sessions are dropped. The launcher
                                           and profile distribution are NOT affected.
                                           The state persists in the config across restarts
mods sessions list|clear                 — active web sessions
mods web                                 — web files status (config/ModsSite/web)
mods web restore                         — overwrite ALL web files from the jar
mods curseforge test [version]           — CF key check + search probes (custom game version)
mods curseforge categories               — dump of CF mod categories (what filters will show)
mods curseforge search <q> [version] [loader] — live CF search with the same code as the site
mods curseforge fingerprint <file|dir>... — murmur2 fingerprints of your jars + a live CF query:
                                            does it know these files (diagnostics for "the mod
                                            is on CF but import marked it local")
mods profile optionals [profile]         — OPTIONAL-MODS DIAGNOSTICS: how the module sees
                                            updateOptional (in memory + straight from
                                            profile.json), which formats were recognized and
                                            why a specific mod stays "required" (what didn't match)
```

## Configuration

File: `config/ModsSite/Config.json` (created on first start; can be edited by
hand, then `mods reload` or a restart).

```jsonc
{
  "enabled": true,                      // module master switch

  "web": {
    "uiEnabled": true,                  // serve the web UI
    "apiEnabled": true,                 // REST API (false = console only)
    "title": "Mods Manager",
    "defaultLang": "ru",                // ru | en (users can switch)
    "cookieSecure": "auto",             // auto | always | never
    "cookieSameSite": "Lax",
    "trustProxyHeaders": "auto",        // auto (follows netty.ipForwarding) | true | false
    "httpsRedirectUrl": "",             // e.g. "https://mods.example.com"
    "securityHeaders": true,
    "cspExtra": "",
    "siteDomains": []                   // domains serving the site at "/" (e.g. "mods.example.com")
  },

  "auth": {
    "mode": "hybrid",                   // hybrid | local | launcher
    "cookieName": "mods_session",
    "sessionTtlMinutes": 720,
    "onlyAdminsCanLogin": false,        // admit admins only
    "totpEnabled": true,                // ask TOTP when a secret is set
    "maxSessionsPerIp": 0,              // 0 = no limit
    "maxFieldLength": 256
  },

  "launcherAuth": {
    "authId": "",                       // auth pair ID from LaunchServer.json ("" = default)
    "adminCheck": {
      "type": "permissions",            // permissions | sql | none
      "permission": "mods.admin",
      "role": "modsadmin",
      "sql": {                          // for type=sql
        "url": "",                      // "" = reuse the launcher's SQL provider
        "user": "", "password": "",
        "query": "SELECT isAdmin FROM users WHERE username = ?",
        "params": ["{login}"],
        "trueValues": ["1", "true", "t", "yes", "y", "admin"]
      }
    },
    "treatNonAdminAs": "viewer",        // viewer | deny
    "extraTotp": { }                    // TOTP secrets for launcher accounts
  },

  "accounts": [                         // local accounts (password -> hash at startup)
    { "login": "admin", "passwordHash": "pbkdf2_sha256$...", "role": "admin", "enabled": true }
  ],

  "superAdmins": [],                     // superadmins — set ONLY here manually: login, from DB or a local record;
                                         // they manage admin roles in "Security -> Roles"

  "security": {
    "enabled": true,                    // IP bans for failed logins
    "maxAttempts": 10,                  // failures within the window before a ban
    "windowMinutes": 10,
    "banScheduleMinutes": [5, 15, 60, 360, 1440],  // escalating durations (min)
    "levelDecayHours": 24,              // level reset after clean time (0 = never)
    "accountLock": {                    // login lockouts (independent of IP)
      "enabled": true, "maxFails": 10, "windowMinutes": 10,
      "lockScheduleMinutes": [10, 30, 240], "levelDecayHours": 24
    }
  },

  "rateLimit": { "enabled": true, "apiPerMinute": 240, "loginPerMinute": 30 },

  "audit": { "enabled": true, "file": "audit.log.jsonl", "maxFileMb": 5, "keepFiles": 3 },

  "logging": {
    "consoleLevel": "warn"              // off | error | warn | all — how much the module
                                        // prints to the server console. "warn" (default):
                                        // errors and warnings only; "all" adds info/debug
                                        // (e.g. the optional-sync trace). Applied immediately,
                                        // managed in Settings -> Updates; the audit file is
                                        // not affected
  },

  "themes": {
    "defaultTheme": "glass",            // theme id for users without a personal choice:
                                        // glass|comic|simple|neon|pixel|terminal or a custom id
    "defaultMode": "dark",              // light | dark — default variant
    "disabled": [],                     // built-in theme ids hidden from EVERYONE
                                        // (players, viewers, admins)
    "custom": [                         // admin-defined themes (Settings -> Appearance);
                                        // every color is #rrggbb
      {
        "id": "night-cherry",           // unique id (auto-generated from the name)
        "name": "Night Cherry",         // display name (up to 40 chars)
        "font": "sans",                 // sans | mono
        "radius": "soft",               // sharp | soft | round — corner style
        "light": {                      // light variant base colors
          "bg": "#F7EDF0", "card": "#FFFFFF", "text": "#33141E",
          "textMuted": "#8A5F6B", "border": "#EBD3DA",
          "accent": "#C2255C", "accent2": "#7C3AED"
        },
        "dark": {                       // dark variant base colors
          "bg": "#17090E", "card": "#24121A", "text": "#F4DCE2",
          "textMuted": "#B08A95", "border": "#3D212C",
          "accent": "#FF5C8A", "accent2": "#B197FC"
        }
      }
    ]
  },

  "mods": {
    "modrinth":  { "enabled": true, "baseUrl": "https://api.modrinth.com/v2",
                   "userAgent": "ModsSite_module/1.0 (LaunchServer; admin@example.com)" },
    "curseforge": { "enabled": true, "apiKey": "", "baseUrl": "https://api.curseforge.com" },
    "updateSourcePriority": ["modrinth", "curseforge"],  // update check order (editable in the UI)
    "defaultChannel": "release",        // release | beta | alpha
    "modsSubdir": "mods",               // updates/<profile>/<subdir>
    "resourcepacksSubdir": "resourcepacks",
    "shadersSubdir": "shaderpacks",     // resource pack and shader folders
    "modsIgnore": [".index"],           // .gitignore patterns: what not to touch in the
                                        // mods folder (default: the .index service folder
                                        // of Sinytra Connector)
    "resourcepacksIgnore": [],          // same for the resourcepacks folder
    "shadersIgnore": [],                // and shaders
    "defaultProjectType": "mod",        // what to search by default: mod | resourcepack | shader
    "suggestions": {                    // suggestion system
      "enabled": true,
      "maxPerUser": 5,                  // PENDING suggestions per user (0 = no limit)
      "bannedUsers": []                 // logins not allowed to suggest (case-insensitive)
    },
    "verifyHash": true,                 // verify SHA after download
    "allowWithoutHash": true,
    "autoRehash": true,                 // recalculate hashes after changes
    "cacheTtlMinutes": 10,              // source API response cache
    "httpTimeoutSeconds": 30,
    "maxConcurrentDownloads": 4,
    "iconProxyEnabled": true,           // serve Modrinth/CF icons via the server
                                        // (these CDNs are blocked for some clients;
                                        // disk cache: config/ModsSite/iconcache)
    "iconProxyHosts": ["cdn.modrinth.com", "media.forgecdn.net",
                       "media.curseforge.com", "cdn.forgecdn.net"],  // subdomains allowed too
    "autoResolveDeps": true,            // auto-link dependencies/conflicts
    "defaultOptionalEnabled": true,     // optional mods enabled for players by default (mark in updateOptional)
    "syncProfileOptional": true,        // sync optional mods with the profile's
                                        // updateOptional (player checkboxes)
    "maxModsPerProfile": 500
  },

  "whitelist": [],                      // IPs/CIDRs never banned (extended via command)
  "blacklist": []                       // IPs/CIDRs always refused
}
```

The modpack file `config/ModsSite/modpacks/<profile>.json` (created
automatically):

```jsonc
{
  "gameVersion": "",   // "" = from the profile
  "loader": "",        // "" = auto (profile property modsLoader / name) | fabric|forge|neoforge|quilt|any
  "modsDir": "",       // "" = mods.modsSubdir
  "resourcepacksDir": "",  // "" = mods.resourcepacksSubdir
  "shaderpacksDir": "",    // "" = mods.shadersSubdir
  "mods": [
    {
      "id": "sodium", "kind": "mod", "source": "modrinth", "projectId": "AANobbMI",
      "name": "Sodium", "optional": false, "enabled": true, "channel": "release",
      "dependencies": ["sodium-extra-if-it-depends"], "conflicts": [],
      "installedFileName": "sodium-fabric-0.5.13+mc1.20.1.jar",
      "installedVersionId": "OihdIimA", "installedVersionNumber": "mc1.20.1-0.5.13-fabric",
      "skippedVersionId": null          // the version postponed via "Postpone"
    }
  ]
}
```

`kind` is the content type (`mod` — the default for entries from older
versions, `resourcepack` — files in `resourcepacks/`, `shader` — in
`shaderpacks/`).

## Backups

Version 1.1.0 adds a per-profile **backup system** (the "Backups" tab, admin
only). A backup is a **real `.mrpack`** (the Modrinth modpack format) stored
in `config/ModsSite/backups/<profile>/<uuid>.mrpack`:

- **mrpack structure**: `modrinth.index.json` at the root (`formatVersion: 1`,
  `game: "minecraft"`, `files[]` with `path`, `hashes` (sha1 + sha512),
  `env`, `downloads`, `fileSize`, and `dependencies` with `minecraft`) plus
  `overrides/` for local files. Backups can be opened in the Modrinth app or
  any other mrpack-compatible tool, and are restored by the module itself.
- **Smart content storage**: mods/resourcepacks/shaders with a
  Modrinth/CurseForge binding are NOT archived — each becomes a `files[]`
  entry with a direct download link (Modrinth CDN /
  CurseForge redirect); on restore the exact same file is re-downloaded and
  verified against the recorded sha1/sha512. If the direct link fails, the
  version is re-fetched by id from the source API (fallback). Files without a
  binding (local mods, configs) go into `overrides/`.
- **Module sidecar**: `modssite.index.json` inside the archive (ignored by
  third-party mrpack tools) stores the backup id/name/date, the list of
  backed-up folders and the modpack snapshot
  (`config/ModsSite/modpacks/<profile>.json`); on restore it is written back,
  `updateOptional` is re-synced, and profile hashes are recalculated (if
  `mods.autoRehash`).
- **Backup folders** are picked **with checkboxes right in the Backups tab** —
  the list is built from the folders that actually exist in the profile build
  folder (`updates/<profile>/`) plus the standard content folders. Empty
  selection (the "Default" button) = `mods`, `resourcepacks`, `shaderpacks`
  with your overrides; tick e.g. `config` or `scripts` as well (up to 16).
- Each row shows the backup **size**, the top shows the **total size of all
  backups**. The name defaults to the current date and time and can be
  renamed. The **Freeze** button protects a backup from accidental deletion
  (frozen backups are also never auto-pruned).
- Automatic pruning: when a profile exceeds `backup.maxBackups` (30), the
  oldest NON-frozen OWN backups are removed first (frozen and foreign mrpack
  files do not consume the quota).
- Restoring returns the selected folders and the modpack to the backup state
  (a true snapshot): linked content is re-downloaded from Modrinth/CurseForge,
  files added to those folders after the backup are removed (ignored patterns
  and temp files are left alone). Files with `env.client = "unsupported"`
  (server-side content) are skipped — a LaunchServer profile is a client
  build. Restores are asynchronous — the tab polls the running job and shows
  a toast with the result (files restored / re-downloaded / removed / failed,
  each failure is also written to the log).
- Backups can be downloaded from the UI as `.mrpack` (up to 128 MB; larger
  ones — take them from `config/ModsSite/backups/` on the server directly).

**Foreign mrpack files**: you can drop ANY valid `.mrpack` into the profile
backup folder (`config/ModsSite/backups/<profile>/`) — including a pack for
a DIFFERENT build downloaded from Modrinth. It appears in the list once the
tab is opened (the meta is built from `modrinth.index.json`; file names with
spaces/unicode are normalized to a safe id) and is restored like any backup:
`overrides/` and `client-overrides/` unpack into the build folder, `files[]`
are downloaded with sha1/sha512 verification, covered folders (top-level path
segments of the manifest) are cleaned of extra files — the build becomes
equal to the foreign pack. The modpack state is rebuilt from the manifest:
files with cdn.modrinth.com / curseforge.com links get source bindings,
the rest become local entries (adopt them later via the "orphan import").
If the pack targets a different Minecraft version, the result message
includes a warning. Frozen and foreign mrpack files are never auto-pruned
(only manual deletion).

Security notes: backup archives are written atomically; on restore every
path from the manifest and every `overrides/` entry is validated (no `..`,
no absolute paths — nothing can escape the profile folder), symlinks are
never archived, direct downloads are only allowed from an https allowlist
(Modrinth CDN, CurseForge mirrors, GitHub) and are size-capped and
hash-verified, and all backup operations are audit-logged.

Config (`mods.backup.*` in Config.json): `enabled` (true), `maxBackups` (30),
`maxFiles` (5000), `maxTotalMb` (2048).

## Update progress (1.1.1)

`POST /mods/api/mods/update` ("Update all" / per-mod "Update") and
`POST /mods/api/mods/apply` (the apply-composition API — since 1.1.3 without
a UI button: restoring missing files is the job of "Folder scan" and
"Update") no longer hang the request
until every download finishes. The server starts a **background job** and
answers immediately; the admin UI opens a progress window and polls
`GET /mods/api/mods/progress` every 0.7 s, so it is always visible:

- current **phase**: preparing → checking (`12/40`) → downloading →
  rehashing → done;
- **per-mod rows**: waiting / checking / downloading (with live byte
  counters, e.g. `3.4 / 7.1 MiB`) / updated (version) / skipped / error;
- overall progress bar (`completed / total`).

Only one background job per profile: a second attempt returns
409 `update_in_progress`, and the UI simply attaches to the running job.
While the job holds the profile lock, other profile-mutating endpoints
(check/add/remove/skip/import/clean/rehash/…) answer 409 `update_in_progress`
instead of blocking; the mod list itself (`GET /mods/api/mods`) stays
available during an update (lock-free snapshot, atomic saves) and carries
the job snapshot, so the progress window reopens automatically after a page
reload. The progress window can be collapsed — the job keeps
running and a toast with the final `updated: X/Y` summary appears anyway.

**Stopping an update (1.1.1).** The progress window has a **Stop** button
(`POST /mods/api/mods/update/stop`): downloads that already started are
finished and installed, mods that never started are cancelled (marked
"cancelled" in the list and removed from the queue) and the final summary
reports how many were finished vs cancelled.

**Fail-safe installs (1.1.1).** A mod file is replaced only after the fresh
download has been fully written and hash-verified, and the replacement itself
is an atomic rename where the filesystem supports it (with a backup/restore
fallback where it does not). The previous file is deleted only after the new
one is in place — so a broken download, a hash mismatch, a timeout or a disk
error can never leave "no mod at all": the old version keeps working. Optional
mods go through the same path, and a stuck per-mod task no longer hides the
results of the rest of the batch.

## Web API

Every response: `{"success": true, "data": ...}` or
`{"success": false, "error": {"code": "...", "message": "..."}}`.
POST requests (except `login`) require the `X-Mods-CSRF` header from the
session data.

| Method | Path | Access | Description |
|---|---|---|---|
| GET | `/mods/api/health` | all | status |
| GET | `/mods/theme.js` | all | theme configuration for the page (default theme/variant, disabled built-ins, custom themes); served before login, never cached |
| POST | `/mods/api/login` | all | login (`login`,`password`,`totp`) |
| POST | `/mods/api/logout` | session | logout |
| GET | `/mods/api/session` | session | session info + CSRF |
| GET | `/mods/api/profiles` | session | profile list |
| GET | `/mods/api/mods?profile=` | session | modpack composition (+ `loaderOverride` — the modpack's explicit loader) |
| GET | `/mods/api/mods/search?profile=&source=&type=&query=&loader=&gameVersion=&categories=&sort=&offset=&limit=` | session | search/catalog (`type` = mod/resourcepack/shader, default mod; without a loader parameter the profile's loader is used — for mods only) |
| GET | `/mods/api/mods/categories?source=&type=` | session | source categories for filters (per content type) |
| POST | `/mods/api/mods/add` | admin | add a mod (`kind` = mod/resourcepack/shader) |
| POST | `/mods/api/mods/remove` | admin | remove mod(s) |
| POST | `/mods/api/mods/settings` | admin | mod settings (optional/channel/deps/conflicts/note) |
| POST | `/mods/api/mods/skip` | admin | "Postpone"/clear it (`{profile,id,skip}`) |
| POST | `/mods/api/mods/resolve-deps` | admin | re-link dependencies from the API |
| POST | `/mods/api/mods/scan` | admin | "Folder scan": rename files with color codes (&a/§c) in names, respond `{renamed, deleted (files deleted manually, with a `canRestore` flag), added (files added manually)}`; handle items via `/mods/update`, `/mods/remove`, `/mods/import`, `/mods/clean` |
| POST | `/mods/api/mods/check` | admin | check updates (the response contains `updatesAvailable`, `relinked` and `missingFiles` — how many entries have no file on disk); 409 `update_in_progress` while a background update job runs |
| POST | `/mods/api/mods/update` | admin | start a **background** update job (mod(s)/all) — responds immediately with `{started:true, type:"update"}`; progress via `/mods/progress` (postponed mods are skipped, marked `postponed`) |
| POST | `/mods/api/mods/update/stop` | admin | request stop of the running update job: started downloads finish, queued ones are cancelled; responds `{stopping:true}` |
| POST | `/mods/api/mods/apply` | admin | start a **background** apply-composition job (download missing files, sync `updateOptional`); responds `{started:true, type:"apply"}`, progress via `/mods/progress`. Since 1.1.3 the UI button is removed — the API is kept for compatibility |
| GET | `/mods/api/mods/progress?profile=...` | admin | snapshot of the background update/apply job: `phase` (`preparing/checking/downloading/rehashing/done/error`), counters, per-mod status and downloaded bytes; `results` when finished |
| GET | `/mods/api/mods/orphans?profile=` | admin | extra files (all content folders; response `{kind,name}[]`) |
| POST | `/mods/api/mods/clean` | admin | delete extra files (`files` — objects `{name,kind}`; strings are treated as mods) |
| POST | `/mods/api/mods/import` | admin | import extra files (same `files` format) |
| POST | `/mods/api/mods/suggest` | session | suggest a project into a profile (`{profile,source,projectId,kind}`; errors `suggest_disabled`/`suggest_banned`/`suggest_limit`/`already_in_modpack`/`already_suggested`) |
| GET | `/mods/api/mods/suggestions?q=&kind=` | admin | suggestion queue (name/user search + kind filter) |
| GET | `/mods/api/mods/suggestions/mine` | session | own suggestions and remaining limit (viewer) |
| POST | `/mods/api/mods/suggestions/decision` | admin | decision `{id, action: "approve"\|"reject"}`: approve adds the mod as REQUIRED and downloads the file immediately (including files missing on "already in modpack" entries); on download failure the response contains `downloadError` (yellow toast with the reason + an audit record) |
| GET/POST | `/mods/api/settings` | admin | module settings: source priority, modpack behavior, console log level, default search type, suggestions (limit/bans/switch), themes (default theme/variant, disabled built-ins, custom theme list) |
| POST | `/mods/api/profiles/reload` | admin | re-read profile configs (profiles/*.json) from disk + adopt optionality from `updateOptional` (response `{profiles, updatedModpacks}`) |
| POST | `/mods/api/profiles/settings` | admin | default modpack loader (`{profile, loader}`: "" — auto, "any" — no filter, fabric/forge/neoforge/quilt) |
| POST | `/mods/api/rehash` | admin | **update hashes** of a profile/all |
| GET | `/mods/api/bans` | admin | bans/lockouts/lists |
| POST | `/mods/api/bans/unban` | admin | unban |
| GET/POST | `/mods/api/ipfilter` | admin | whitelist/blacklist |
| GET | `/mods/api/audit?offset=&limit=` | admin | audit journal |
| GET | `/mods/api/users` | admin | local accounts |
| POST | `/mods/api/users/password` | admin | change a local account's password |
| GET | `/mods/api/backups?profile=` | admin | backup list: `{backups[{id,name,createdAt,size,frozen,remoteCount,fileCount}], totalSize, job, lastResult, folders, profileFolders[{name,exists}]}` |
| POST | `/mods/api/backups/create` | admin | create a backup `{profile, name}` (async, HTTP 202; name defaults to date+time) |
| POST | `/mods/api/backups/rename` | admin | rename `{profile, id, name}` |
| POST | `/mods/api/backups/freeze` | admin | freeze/unfreeze `{profile, id, frozen}` |
| POST | `/mods/api/backups/delete` | admin | delete `{profile, id}` (refuses with `backup_frozen` on frozen ones) |
| POST | `/mods/api/backups/restore` | admin | restore `{profile, id}` (async, HTTP 202) |
| POST | `/mods/api/backups/folders` | admin | set backup folders `{profile, folders[]}` (relative paths, up to 16) |
| GET | `/mods/api/backups/download?profile=&id=` | admin | download a backup zip (max 128 MB, `backup_too_large` otherwise) |

---

State files:

```
config/ModsSite/Config.json        — settings
config/ModsSite/ipfilter.json      — bans/whitelist/blacklist
config/ModsSite/sessions.json      — sessions (token hashes)
config/ModsSite/modpacks/*.json    — modpack compositions
config/ModsSite/backups/<profile>/ — profile backups (*.mrpack + *.json meta)
config/ModsSite/audit.log.jsonl    — audit journal
config/ModsSite/web/*              — web UI (editable on disk!)
```

Project layout:

```
src/main/java/pro/gravit/launchermodules/modssite/
    ModsSiteModule.java      — entry point, lifecycle, wiring
    ModsSiteConfig.java      — config (Gson POJO)
    ModsSiteLogLevels.java   — console log level gate (off/error/warn/all)
    auth/                    — login, sessions, PBKDF2, TOTP, launcher account checks
    security/                — escalating IP bans, rate limit, audit
    mods/                    — Modrinth/CurseForge API, modpacks, updates, hashes
    web/                     — HTTP servlet (static files + REST + theme.js)
    commands/                — "mods ..." console commands
src/main/resources/web/      — frontend (index.html, app.js, i18n.js, style.css)
gradlew, gradlew.bat, gradle/ — Gradle Wrapper (build without installing Gradle)
docs/README_RU.md            — Russian version of this README
```

The distribution zip additionally carries the pre-built `ModsSite_module.jar`
at the root — ready to drop into `modules/`.
