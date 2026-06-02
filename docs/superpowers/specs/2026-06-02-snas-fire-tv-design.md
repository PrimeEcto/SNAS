# SNAS Fire TV Design

## Purpose

SNAS is a polished Fire TV application for browsing and watching the owner's personal movie and TV library using IMDb IDs as the common identifier. The app should feel like a dedicated streaming service: fast, remote-friendly, visually refined, and easy to install from GitHub Releases with Downloader on Fire TV.

The system must avoid paid APIs and avoid requiring the owner to maintain a custom backend server. For the private beta phase, a public APK should not be useful to random users without an unlock code. That protection must be easy to remove later when the project becomes fully public.

## Hard Requirements

- Build a complete Fire TV APK for movies and TV shows.
- Use only playable items verified against the embed host.
- Do not require paid APIs or user-provided third-party API keys.
- Do not require new services on the owner's existing server.
- Publish installable APKs through GitHub Releases.
- Keep catalog automation on GitHub Actions and GitHub Pages.
- Treat UI polish, smooth focus movement, and playback quality as first-class product requirements.
- Use a removable catalog access layer so private beta protection can become public catalog access later without rewriting the app.

## Repository And Delivery Target

The target repository is `PrimeEcto/SNAS`. It is currently an empty public GitHub repository, so the project can be structured from scratch.

The repository will contain:

- Android Fire TV app source.
- Python catalog scanner.
- GitHub Actions workflows for catalog publishing and APK releases.
- Documentation for installing the latest release APK with Downloader.
- Static generated catalog output for GitHub Pages, or workflow-generated catalog artifacts published to Pages.

## High-Level Architecture

SNAS has three major parts:

1. Catalog builder
   A scheduled GitHub Actions job downloads IMDb non-commercial datasets, chooses candidate movie and TV IDs, probes the embed host, and publishes only playable catalog entries.

2. Static catalog hosting
   GitHub Pages serves catalog JSON. During the private phase these files can be encrypted. During the public phase the same app can switch to plain JSON.

3. Fire TV APK
   A native Android TV application downloads and caches the catalog, provides a polished TV interface, and launches playback through the best available playback engine.

The app should never discover the full library live during normal browsing. Normal browsing must use the prebuilt catalog so Fire TV navigation stays fast.

## URL Model

IMDb IDs are the stable key across all systems.

Examples:

- IMDb movie page: `https://www.imdb.com/title/tt0117571/`
- Play domain route: `https://www.playimdb.com/title/tt0117571/`
- Movie embed: `https://streamimdb.ru/embed/movie/tt0117571`
- TV embed: `https://streamimdb.ru/embed/tv/tt0460681`

Movie entries use `/embed/movie/{imdbId}`. TV entries use `/embed/tv/{imdbId}`.

## Playability Detection

The embed host returns HTTP 200 for both playable and missing pages, so HTTP status is not enough.

Observed working movie page:

- `https://streamimdb.ru/embed/movie/tt0117571`
- Rendered title: `Scream 1996`
- Contains a real player surface such as `video id="video"`, `player-wrapper`, duration text, quality options, and poster/backdrop references.

Observed missing movie page:

- `https://streamimdb.ru/embed/movie/tt41021123`
- Contains `404`, `Content not found`, and the IMDb ID.
- Does not expose a playable video element.

Observed working TV page:

- `https://streamimdb.ru/embed/tv/tt0460681`
- Contains a real player and season/episode selectors with `data-season` and `data-episode` values.

The scanner should classify an item as playable only when positive player markers are present and missing-content markers are absent. The initial marker set should include:

- Positive: `video id="video"`, `player-wrapper`, meaningful page title, duration, quality choices, or poster URL.
- Negative: `Content not found`, prominent `404`, missing player wrapper, or generic title-only player page.

## Catalog Builder

The catalog builder will be a Python script run by GitHub Actions. It should be safe, resumable, and respectful of the embed host.

Inputs:

- IMDb non-commercial datasets from `datasets.imdbws.com`.
- Existing scan state from the repository, GitHub Pages artifact, or Actions cache.
- Optional manual seed list for known working IMDb IDs.

Candidate selection:

- Include `movie` and `tvSeries` from IMDb title basics.
- Exclude adult titles.
- Prefer titles with real start years.
- Prioritize by IMDb vote count from title ratings so common searches become available first.
- Keep the candidate queue resumable instead of trying to test every IMDb ID in one run.

Scan behavior:

- Probe movie candidates at `/embed/movie/{imdbId}`.
- Probe TV candidates at `/embed/tv/{imdbId}`.
- Apply concurrency limits and per-run time budgets.
- Record tested IDs, playable IDs, failed IDs, last checked time, and failure reason.
- Retry stale failures after a cooldown because host availability may change.
- Preserve known playable items unless they fail repeated rechecks.

Catalog output:

- `catalog/index.json`
- `catalog/movies.json`
- `catalog/shows.json`
- `catalog/search.json`
- `catalog/genres/{genre}.json`
- `catalog/state.json` for scanner state, if safe to publish.

The published app-facing catalog should be compact and optimized for Fire TV startup. The scanner state can be stored separately if it becomes too large or too implementation-specific.

## Metadata

Primary metadata should come from IMDb's free non-commercial datasets where possible:

- IMDb ID
- Title
- Title type
- Start year
- End year for shows when available
- Runtime
- Genres
- Average rating
- Vote count

Poster/backdrop metadata should initially come from the working embed page when exposed there. The observed player pages included image URLs from `image.tmdb.org`, but the app should treat those as discovered media URLs from the playable page rather than relying on a paid API.

IMDb page scraping should not be the default metadata strategy. It is fragile and likely to create unnecessary blocking or maintenance problems.

## Fire TV App

The app will be a native Kotlin Android TV application. It should launch directly into the browsing experience rather than a landing page.

Core screens:

- Home
- Movies
- Shows
- Genres
- Search
- Watchlist
- Details
- Player
- Settings
- Unlock screen when private catalog mode is active

Home should include:

- Featured or recently added row.
- Continue watching.
- Movies row.
- Shows row.
- Genre rows.
- Watchlist row when populated.

Details should show:

- Poster or backdrop.
- Title.
- Year.
- Runtime when known.
- Genres.
- IMDb rating and vote count when available.
- Play action.
- Watchlist action.
- Resume action when progress exists.

TV handling:

- V1 should support TV shows from day one.
- The app can initially track a show-level watch state while the embedded player owns season and episode selection.
- If the scanner reliably extracts season and episode lists, native episode browsing can be added without changing the catalog model.

Local data:

- Cached catalog files.
- Unlock state.
- Watchlist.
- Continue-watching state.
- Last played item.
- Recent searches.
- User settings.

## UI And Interaction Quality

SNAS should feel like a polished streaming app, not a generic Android sample.

UI principles:

- D-pad first interaction.
- Strong, attractive focus states.
- Smooth row scrolling.
- Predictable Back behavior.
- Large readable typography for TV distance.
- No tiny touch-only controls.
- Fast startup with cached catalog fallback.
- Loading skeletons instead of blank screens.
- Subtle press/focus animations that make the app feel responsive.
- Search input that feels smooth with a Fire TV remote.
- Details pages that make poster art, backdrop art, and play actions clear without clutter.

The experience target is closer to Netflix-style browsing than a file browser. The design should be quiet, cinematic, and efficient, with high attention to focus restoration when navigating back from details or playback.

## Playback Strategy

The preferred playback engine is Media3/ExoPlayer when direct HLS, DASH, or MP4 URLs are available. ExoPlayer gives strong control over adaptive streaming, hardware decoding, track selection, and quality constraints.

However, the observed embed player creates browser-only `blob:` video URLs after JavaScript execution. ExoPlayer cannot play those blob URLs directly. Because of that, the v1 playback strategy is:

1. Use a full-screen in-app WebView pointed directly at the embed URL.
2. Enable JavaScript, DOM storage, media playback, and hardware acceleration.
3. Avoid wrapping the player in another page.
4. Handle full-screen video and Back exit cleanly.
5. Attempt to select the best quality exposed in the player DOM, such as `1080p`, then `720p`, then `Auto`.
6. Fall back to the player's `Auto` mode if quality selection is unreliable.

The app should contain a `PlaybackEngine` abstraction with at least:

- `WebViewPlaybackEngine`
- `Media3PlaybackEngine` placeholder or future implementation

This keeps the door open for direct ExoPlayer playback if the scanner later discovers stable HLS, DASH, or MP4 URLs.

## Playback Quality Requirements

Playback quality is a core requirement because the user is sensitive to poor Fire TV playback quality and has observed that VLC can look worse than ExoPlayer on the same device.

The app should optimize for:

- Hardware-accelerated rendering.
- Highest available player quality when possible.
- Minimal scaling artifacts.
- Stable full-screen behavior.
- Smooth pause/resume.
- No extra overlays during playback.
- Correct handling of Fire TV sleep/wake and app resume.

The first implementation should include practical Fire TV test notes for:

- Whether 1080p is selected when available.
- Whether `Auto` chooses acceptable quality.
- Whether the WebView pipeline looks worse than expected.
- Whether direct stream extraction becomes necessary for quality.

If WebView quality is not acceptable on device, the next design iteration should focus on extracting direct stream URLs and moving playback to Media3/ExoPlayer.

## Catalog Protection

The private beta should not hardcode a permanent secret in the APK. Public APKs can be inspected, so a hardcoded catalog key would not be real protection and would be annoying to remove later.

The app should define a removable catalog access layer:

- `UnlockedPublic`
  Loads plain JSON catalog files from GitHub Pages.

- `PassphraseEncrypted`
  Downloads encrypted catalog files and prompts the owner for an unlock code on first launch.

In encrypted mode:

- GitHub Actions encrypts catalog files before publishing.
- The unlock code derives the local decryption key.
- The app stores only local unlock state, not the raw passphrase.
- A public user can install the APK but cannot load the catalog without the unlock code.

When the project becomes fully public, the app should be switched to `UnlockedPublic` with a configuration change and the catalog workflow should publish plain JSON.

## GitHub Actions

Two main workflows are needed.

### Catalog Workflow

`catalog.yml`

- Runs on a schedule.
- Runs manually from the Actions tab.
- Downloads IMDb datasets.
- Builds or updates the scan queue.
- Probes a limited number of movie and TV candidates per run.
- Updates scanner state.
- Publishes catalog files to GitHub Pages.
- Encrypts catalog output when private catalog mode is enabled.

The workflow must not require paid API keys.

### Release Workflow

`release.yml`

- Runs when a release tag is pushed or manually from Actions.
- Builds the Android APK.
- Attaches the APK to a GitHub Release.
- Makes the release URL easy to use from Fire TV Downloader.

Initial signing can use a generated development keystore for simplicity. Before long-term public release, the project should move to a stable signing key stored in GitHub Secrets so users can upgrade without uninstalling.

## Installation Flow

Fire TV installation should be documented as:

1. Open Downloader on Fire TV.
2. Enter the latest GitHub Release APK URL.
3. Install the APK.
4. Open SNAS.
5. Enter the unlock code once during private beta.
6. Browse and play the catalog.

The README should include the exact latest-release URL pattern after release automation exists.

## Testing And Acceptance Criteria

Scanner tests:

- Working movie example is classified as playable.
- Missing movie example is classified as not playable.
- Working TV example is classified as playable.
- TV season/episode markers are extracted when present.
- HTTP 200 missing pages are still rejected.
- Scan state is resumable.

Catalog tests:

- Generated catalog JSON is valid.
- Search index includes title, year, type, and genres.
- Genre files contain only playable entries.
- Encrypted catalog decrypts with the unlock code.
- Public mode can load plain catalog JSON without unlock logic.

App tests:

- APK builds locally and in GitHub Actions.
- App launches on Fire TV.
- App loads catalog from GitHub Pages.
- App uses cached catalog when offline.
- D-pad navigation works across home, rows, details, search, and settings.
- Focus returns to the expected item after Back.
- Watchlist persists.
- Continue watching persists.
- WebView player opens full-screen.
- Back exits player without killing the app.
- Best quality selection is attempted.
- App survives pause/resume and sleep/wake.

Release tests:

- GitHub Actions creates an APK.
- GitHub Release contains the APK.
- APK installs through Downloader.
- No private token is committed to the repository.

## Risks And Mitigations

Risk: The embed host changes HTML.
Mitigation: Keep player detection marker-based and add regression fixtures from known working/missing pages.

Risk: WebView playback quality is not good enough.
Mitigation: Keep playback behind a `PlaybackEngine` abstraction and prioritize direct stream extraction plus Media3/ExoPlayer if needed.

Risk: GitHub Pages public catalog exposes the library during private beta.
Mitigation: Encrypt catalog files and require unlock code until public mode is enabled.

Risk: Scanner takes a long time to discover the library.
Mitigation: Prioritize high-vote IMDb titles, support manual seed IDs, and keep resumable state.

Risk: Generated signing keys prevent smooth app upgrades.
Mitigation: Use generated signing only for early sideload testing, then switch to a stable release keystore before wider use.

Risk: IMDb datasets do not include poster art.
Mitigation: Capture poster/backdrop URLs from playable embed pages when available and keep UI graceful without art.

## Out Of Scope For First Implementation

- Paid metadata APIs.
- User accounts.
- Multi-user profiles.
- Chromecast support.
- Server-side database or custom backend service.
- Native episode browser unless episode extraction is reliable early.
- Guaranteed exact playback timestamp tracking inside WebView.

## Open Implementation Notes

- Confirm the best Android minimum SDK for current Fire TV devices.
- Choose the Android UI stack during implementation planning, likely Kotlin plus TV-focused Compose components.
- Decide whether catalog state should be committed, cached as an artifact, or published separately.
- Decide the private beta unlock passphrase outside the repository and never commit it.
- Confirm whether direct HLS/DASH/MP4 URLs can be discovered safely from the embed player.

