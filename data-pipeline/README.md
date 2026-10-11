# WuWa Tracker data pipeline

This pipeline collects public Wuthering Waves news and schedule information, normalizes it, and writes `feed.json` for the Android app. It is an unofficial community project; source content can be incomplete or incorrect.

## Sources and labels

Sources are configured in [`sources.json`](sources.json). The pipeline currently reads public pages and feeds, including Google News RSS search results, Reddit RSS feeds, and public schedule or game-information sites. It does not require an X/Twitter API token and does not bypass site access controls.

An item is labeled `OFFICIAL` only when publisher metadata identifies an approved Kuro Games domain or the official Wuthering Waves X account; a source's configured category alone is not enough. Items from sources configured as `leak-community`, and other items whose title or summary contains leak-related terms, are labeled `LEAK`. This label does not confirm a claim.

The pipeline reads Google News RSS search results, Reddit RSS, selected public HTML pages and schedule snapshots, and Kuro Games' official news JSON index. Official news items with dates within 60 days of feed generation are included. Each remote response is limited to 10 MiB. Source failures are recorded in `sourceHealth` where possible, and one failed source does not prevent other sources from being processed.

The live game version is taken only from a dated schedule snapshot marked as current and active at feed-generation time; an official announcement of a future version does not by itself make that version current. Leak-community searches are narrowed to the next minor version. When an article states an eligible version, the pipeline adds it as `targetVersion` metadata for the app to use when filtering.

## Feed contents

The generated `feed.json` uses schema version 3 and includes:

- `news` — attributed articles and posts; eligible version targeting is included as `targetVersion`
- `versions`, `banners`, `activeBanners`, and `upcomingBanners` — version and banner information with source evidence
- `scheduleSnapshots` and `weaponDetails` — source-derived schedule snapshots and character-to-weapon details
- `resonators` and `upcomingResonators` — extracted character claims
- `events` — source-derived event and endgame dates
- `sourceHealth` and `policy` — source status and interpretation rules

Records retain source URLs and confidence information. Multiple sources can strengthen a claim, but community or leak claims are not automatically made official.

## Run locally

Run these commands from the repository root. Python 3.12 is used by the GitHub workflow.

```powershell
py -3.12 -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install --require-hashes -r data-pipeline/requirements.txt
python -m unittest discover -s tests -v
python data-pipeline/aggregator.py
```

The aggregator writes `data-pipeline/feed.json`. It makes requests to the configured public sources, so source availability and returned results can vary. The tracked `feed.json` may be an empty seed file; the workflow runs the aggregator and publishes that run's generated feed to Pages. It does not commit the generated feed back to the repository.

### Update Python dependencies

Declare direct dependency ranges in `requirements.in`; commit both that file and the generated hash-locked `requirements.txt`. To regenerate the lock from the repository root:

```powershell
python -m pip install pip-tools
python -m piptools compile --generate-hashes --output-file=data-pipeline/requirements.txt --strip-extras data-pipeline/requirements.in
```

Review the resolved versions before committing. Install the resulting lock with `--require-hashes` as shown above.

## GitHub Actions and Pages

The `WuWa Intelligence Feed` workflow runs daily at 00:00 UTC and can also be started manually from the Actions tab. It installs the hash-locked dependencies, runs the security regression tests, builds the feed, and uploads **only** `feed.json` to GitHub Pages. A separate deployment job publishes that artifact with the Pages permissions it needs. The workflow does not commit the generated feed back to the repository.

After a successful Pages deployment, the Android app can retrieve the feed at:

```text
https://<github-user>.github.io/<repository>/feed.json
```

Do not commit credentials, signing keys, private data, or local build configuration. The published feed and its source links are public.
