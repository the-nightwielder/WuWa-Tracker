import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "data-pipeline"))
from aggregator import _game8_countdown_seconds, _game8_server_times, _harmonize_prydwen_rows, _remove_stale_prydwen_rows, _source_health_entry, banner_snapshot, game8_banner_snapshot, prydwen_banner_snapshot


GAME8_OVERVIEW_HTML = """
<p>Version 3.7 Details are Out</p>
<h2>Current Banners in Wuthering Waves</h2>
<h3>Banner Schedule and Countdown</h3>
<table>
  <tr><td>Banner Schedule Duration</td></tr>
  <tr><td>September 30, 2026 to October 22, 2026</td></tr>
  <tr><td>America</td><td>11d 5h 31m 30s</td></tr>
  <tr><td>Europe</td><td>10d 23h 31m 30s</td></tr>
  <tr><td>Asia</td><td>10d 16h 31m 30s</td></tr>
  <tr><td>HMT / SEA</td><td>10d 16h 31m 30s</td></tr>
</table>
<h3>Phase 1 - Hsin with Iuno and Chisa Reruns</h3>
<table>
  <tr><td>Character Banners</td><td>Featured Characters</td></tr>
  <tr><td>5★ Limited Rate-Up:</td><td>
    <a href="/games/Wuthering-Waves/archives/hsin"><img alt="Wuthering Waves - Hsin"/>Hsin</a>
    <a href="/games/Wuthering-Waves/archives/iuno"><img alt="Wuthering Waves - Iuno"/>Iuno</a>
    <a href="/games/Wuthering-Waves/archives/chisa"><img alt="Wuthering Waves - Chisa"/>Chisa</a>
    4★ Rate-Up: <a href="/games/Wuthering-Waves/archives/buling">Buling</a>
  </td></tr>
</table>
<h2>Next Upcoming Banners in Wuthering Waves</h2>
<h3>Suoming with Multiple Reruns</h3>
<table>
  <tr><td>Phase 2 Banners</td><td>(October 22, 2026 - November 11, 2026)</td></tr>
  <tr><td>Character Banners</td><td>Featured Characters</td></tr>
  <tr><td>5★ Limited Rate-Up:</td><td>
    <a href="/games/Wuthering-Waves/archives/suoming"><img alt="Wuthering Waves - Suoming"/>Suoming</a>
    <a href="/games/Wuthering-Waves/archives/lynae"><img alt="Wuthering Waves - Lynae"/>Lynae</a>
    <a href="/games/Wuthering-Waves/archives/lucilla"><img alt="Wuthering Waves - Lucilla"/>Lucilla</a>
    4★ Rate-Up: <a href="/games/Wuthering-Waves/archives/taoqi">Taoqi</a>
  </td></tr>
</table>
"""


class Game8ScheduleParsingTest(unittest.TestCase):
    def test_evergreen_overview_countdowns_build_current_and_upcoming_server_schedules(self):
        overview_url = "https://game8.co/games/Wuthering-Waves/archives/453303"
        now = "2026-10-11T09:04:41.579960Z"
        overview = BeautifulSoup(GAME8_OVERVIEW_HTML, "html.parser")

        snapshots = game8_banner_snapshot(
            {"id": "game8-banner-schedule"}, overview, overview.get_text(" ", strip=True), overview_url, now
        )

        self.assertEqual(["Hsin, Iuno, Chisa", "Suoming, Lynae, Lucilla"], [row["title"] for row in snapshots])
        self.assertEqual([1, 2], [row["phase"] for row in snapshots])
        self.assertEqual(["CURRENT", "UPCOMING"], [row["scheduleStatus"] for row in snapshots])
        current, upcoming = snapshots
        self.assertEqual({"AMERICA", "EUROPE", "ASIA", "HMT", "SEA"}, set(current["serverTimes"]))
        self.assertEqual("2026-10-22T01:36:11.579960Z", current["serverTimes"]["SEA"]["endAt"])
        self.assertEqual("2026-09-30T01:36:11.579960Z", current["serverTimes"]["SEA"]["startAt"])
        self.assertNotEqual(current["serverTimes"]["AMERICA"], current["serverTimes"]["SEA"])
        self.assertEqual(current["serverTimes"]["HMT"], current["serverTimes"]["SEA"])
        self.assertEqual(current["serverTimes"]["SEA"]["endAt"], upcoming["serverTimes"]["SEA"]["startAt"])
        self.assertEqual("2026-11-11T01:36:11.579960Z", upcoming["serverTimes"]["SEA"]["endAt"])
        self.assertIn("published calendar-day spans", upcoming["timingBasis"])

    def test_all_banners_current_and_next_link_order_is_discovered(self):
        overview_url = "https://game8.co/games/Wuthering-Waves/archives/453303"
        root = BeautifulSoup(
            '<a href="/games/Wuthering-Waves/archives/453303">All Banners - Current, Upcoming, and Next (Gacha)</a>',
            "html.parser",
        )
        response = SimpleNamespace(encoding="utf-8", url=overview_url)
        with patch("aggregator._bounded_game8_get", return_value=(response, GAME8_OVERVIEW_HTML.encode())):
            snapshots = game8_banner_snapshot(
                {"id": "game8-banner-schedule"}, root, root.get_text(" ", strip=True),
                "https://game8.co/games/Wuthering-Waves/archives", "2026-10-11T09:04:41.579960Z"
            )
        self.assertEqual(2, len(snapshots))
        self.assertEqual(overview_url, snapshots[0]["sourceUrls"][0])

    def test_stable_overview_fallback_is_used_when_archive_has_no_link(self):
        overview_url = "https://game8.co/games/Wuthering-Waves/archives/453303"
        root = BeautifulSoup("<p>Game8 archive</p>", "html.parser")
        response = SimpleNamespace(encoding="utf-8", url=overview_url)
        with patch("aggregator._bounded_game8_get", return_value=(response, GAME8_OVERVIEW_HTML.encode())):
            snapshots = game8_banner_snapshot(
                {"id": "game8-banner-schedule", "overviewUrl": overview_url}, root,
                root.get_text(" ", strip=True), "https://game8.co/games/Wuthering-Waves/archives",
                "2026-10-11T09:04:41.579960Z",
            )
        self.assertEqual(2, len(snapshots))
        self.assertEqual(overview_url, snapshots[0]["sourceUrls"][0])

    def test_direct_overview_is_tried_when_archive_index_fetch_fails(self):
        overview_url = "https://game8.co/games/Wuthering-Waves/archives/453303"
        response = SimpleNamespace(encoding="utf-8", url=overview_url)
        with patch("aggregator.bounded_get", side_effect=[OSError("archive index unavailable"),
                                                           (response, GAME8_OVERVIEW_HTML.encode())]):
            snapshots = banner_snapshot({
                "id": "game8-banner-schedule", "snapshotType": "game8",
                "url": "https://game8.co/games/Wuthering-Waves/archives", "overviewUrl": overview_url,
            })
        self.assertEqual(2, len(snapshots))
        self.assertEqual({"AMERICA", "EUROPE", "ASIA", "HMT", "SEA"}, set(snapshots[0]["serverTimes"]))

    def test_labeled_countdown_card_layout_is_parsed(self):
        self.assertEqual(11 * 86400 + 5 * 3600 + 31 * 60 + 30,
                         _game8_countdown_seconds("Countdown: 11 days, 5 hours, 31 minutes and 30 seconds remaining."))
        html = """
        <h2>Current Banners in Wuthering Waves</h2>
        <h3>Banner Schedule and Countdown</h3>
        <div><p>September 30, 2026 to October 22, 2026</p>
          <div><span>America</span><span>Countdown: 11 days, 5 hours, 31 minutes and 30 seconds remaining.</span></div>
          <div><span>Europe</span><span>Countdown: 10 days, 23 hours, 31 minutes and 30 seconds remaining.</span></div>
          <div><span>Asia</span><span>Countdown: 10 days, 16 hours, 31 minutes and 30 seconds remaining.</span></div>
          <div><span>HMT / SEA</span><span>Countdown: 10 days, 16 hours, 31 minutes and 30 seconds remaining.</span></div>
        </div>
        <h3>Phase 1 - Hsin</h3>
        <table><tr><td>Character Banners</td><td>Featured Characters</td></tr>
          <tr><td>5★ Limited Rate-Up:</td><td><a href="/games/Wuthering-Waves/archives/hsin"><img alt="Hsin"/></a></td></tr></table>
        <h2>Next Upcoming Banners in Wuthering Waves</h2>
        <h3>Phase 2 Banners</h3><p>October 22, 2026 - November 11, 2026</p>
        <table><tr><td>Character Banners</td><td>Featured Characters</td></tr>
          <tr><td>5★ Limited Rate-Up:</td><td><a href="/games/Wuthering-Waves/archives/suoming"><img alt="Suoming"/></a></td></tr></table>
        """
        soup = BeautifulSoup("<p>Version 3.7 details</p>" + html, "html.parser")
        now = "2026-10-11T09:04:41.579960Z"
        snapshots = game8_banner_snapshot(
            {"id": "game8-banner-schedule"}, soup, soup.get_text(" ", strip=True),
            "https://game8.co/games/Wuthering-Waves/archives/453303", now,
        )
        self.assertEqual(2, len(snapshots))
        self.assertEqual({"AMERICA", "EUROPE", "ASIA", "HMT", "SEA"}, set(snapshots[0]["serverTimes"]))
        self.assertEqual(snapshots[0]["serverTimes"]["HMT"], snapshots[0]["serverTimes"]["SEA"])
        self.assertEqual(snapshots[0]["serverTimes"]["SEA"]["endAt"], snapshots[1]["serverTimes"]["SEA"]["startAt"])

    def test_alternate_live_heading_and_roman_phase_labels_are_parsed(self):
        html = GAME8_OVERVIEW_HTML.replace("Current Banners in Wuthering Waves", "Active Character Banners")
        html = html.replace("Next Upcoming Banners in Wuthering Waves", "Upcoming Character Banners")
        html = html.replace("Phase 2 Banners", "Phase II Banners")
        soup = BeautifulSoup(html, "html.parser")
        snapshots = game8_banner_snapshot(
            {"id": "game8-banner-schedule"}, soup, soup.get_text(" ", strip=True),
            "https://game8.co/games/Wuthering-Waves/archives/453303", "2026-10-11T09:04:41.579960Z",
        )
        self.assertEqual(2, len(snapshots))
        self.assertEqual([1, 2], [row["phase"] for row in snapshots])
        self.assertEqual("UPCOMING", snapshots[1]["scheduleStatus"])

    def test_game8_times_attach_to_wuwabuild_rows_by_unique_version_phase_and_status(self):
        times = {server: {"startAt": "2026-09-30T01:00:00Z", "endAt": "2026-10-22T01:00:00Z"}
                 for server in ("AMERICA", "EUROPE", "ASIA", "HMT", "SEA")}
        wuwabuild = {
            "snapshotType": "wuwabuild", "title": "Hsin, Chisa, Iuno", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "WuWaBuild banner schedule",
            "sourceUrls": ["https://www.wuwabuild.com/banners"],
            "startAt": "2026-09-30T00:00:00Z", "endAt": "2026-10-22T23:59:59Z",
        }
        game8 = {
            "snapshotType": "game8", "title": "Hsin, Chisa, Iuno", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "Game8 Banner Schedule",
            "sourceUrls": ["https://game8.co/current"], "serverTimes": times,
            "startAt": "2026-09-30T01:00:00Z", "endAt": "2026-10-22T01:00:00Z",
        }

        _harmonize_prydwen_rows([wuwabuild, game8])

        self.assertEqual(times, wuwabuild["serverTimes"])
        self.assertEqual(game8["startAt"], wuwabuild["startAt"])
        self.assertEqual(game8["endAt"], wuwabuild["endAt"])
        self.assertIn("Game8", wuwabuild["sourceLabel"])
        self.assertEqual(["https://www.wuwabuild.com/banners", "https://game8.co/current"], wuwabuild["sourceUrls"])

    def test_game8_times_attach_to_prydwen_row_when_roster_differs_but_phase_is_unique(self):
        times = {server: {"startAt": "2026-09-30T01:00:00Z", "endAt": "2026-10-22T01:00:00Z"}
                 for server in ("AMERICA", "EUROPE", "ASIA", "HMT", "SEA")}
        game8 = {
            "snapshotType": "game8", "title": "Hsin, Chisa, Iuno", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "Game8 Banner Schedule",
            "sourceUrls": ["https://game8.co/current"], "confidence": 0.9, "serverTimes": times,
            "startAt": "2026-09-30T01:00:00Z", "endAt": "2026-10-22T01:00:00Z",
        }
        prydwen = {
            "snapshotType": "prydwen", "title": "Hsin, Chisa", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "Prydwen Banner Overview",
            "sourceUrls": ["https://www.prydwen.gg/wuthering-waves/banners"], "confidence": 0.9,
        }

        _harmonize_prydwen_rows([game8, prydwen])

        self.assertEqual(times, prydwen["serverTimes"])
        self.assertEqual("2026-09-30T01:00:00Z", prydwen["startAt"])
        self.assertEqual("2026-10-22T01:00:00Z", prydwen["endAt"])

    def test_ambiguous_game8_phase_times_are_not_guessed(self):
        times = {server: {"startAt": "2026-09-30T01:00:00Z", "endAt": "2026-10-22T01:00:00Z"}
                 for server in ("AMERICA", "EUROPE", "ASIA", "HMT", "SEA")}
        game8_rows = [{
            "snapshotType": "game8", "title": title, "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "Game8 Banner Schedule",
            "sourceUrls": ["https://game8.co/" + title], "confidence": 0.9, "serverTimes": times,
            "startAt": "2026-09-30T01:00:00Z", "endAt": "2026-10-22T01:00:00Z",
        } for title in ("Other, Names, Here", "Different, Names, Here")]
        wuwabuild = {
            "snapshotType": "wuwabuild", "title": "Hsin, Chisa, Iuno", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "WuWaBuild banner schedule", "sourceUrls": [],
        }
        prydwen = {
            "snapshotType": "prydwen", "title": "Hsin, Chisa", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "Prydwen Banner Overview",
            "sourceUrls": ["https://www.prydwen.gg/wuthering-waves/banners"], "confidence": 0.9,
        }

        _harmonize_prydwen_rows([*game8_rows, wuwabuild, prydwen])

        self.assertFalse(prydwen.get("serverTimes"))

    def test_overview_is_rejected_cleanly_if_any_server_timing_is_missing(self):
        html = GAME8_OVERVIEW_HTML.replace(
            '  <tr><td>HMT / SEA</td><td>10d 16h 31m 30s</td></tr>', ''
        )
        soup = BeautifulSoup(html, "html.parser")
        self.assertIsNone(game8_banner_snapshot(
            {"id": "game8-banner-schedule"}, soup, soup.get_text(" ", strip=True),
            "https://game8.co/games/Wuthering-Waves/archives/453303", "2026-10-11T09:04:41.579960Z",
        ))

    def test_extracts_all_server_times_and_keeps_hmt_sea_as_separate_keys(self):
        soup = BeautifulSoup("""
            <table>
              <tr><td>America (UTC-4)</td><td>Oct 11, 2026 at 10:00 AM - Oct 22, 2026 at 9:59 AM</td></tr>
              <tr><td>Europe (UTC+2)</td><td>Oct 11, 2026 at 10:00 AM - Oct 22, 2026 at 9:59 AM</td></tr>
              <tr><td>Asia (UTC+9)</td><td>Oct 11, 2026 at 10:00 AM - Oct 22, 2026 at 9:59 AM</td></tr>
              <tr><td>HMT / SEA (UTC+8)</td><td>Oct 11, 2026 at 10:00 AM - Oct 22, 2026 at 9:59 AM</td></tr>
            </table>
        """, "html.parser")

        times = _game8_server_times(soup)

        self.assertEqual({"AMERICA", "EUROPE", "ASIA", "HMT", "SEA"}, set(times))
        self.assertEqual("2026-10-11T14:00:00Z", times["AMERICA"]["startAt"])
        self.assertEqual("2026-10-11T08:00:00Z", times["EUROPE"]["startAt"])
        self.assertEqual("2026-10-11T01:00:00Z", times["ASIA"]["startAt"])
        self.assertEqual(times["HMT"], times["SEA"])
        self.assertEqual("2026-10-11T02:00:00Z", times["SEA"]["startAt"])
        self.assertEqual("2026-10-22T01:59:00Z", times["SEA"]["endAt"])

    def test_combined_hmt_sea_row_wins_if_site_lists_individual_rows_too(self):
        soup = BeautifulSoup("""
            <table>
              <tr><td>HMT (UTC+8)</td><td>Oct 11, 2026 at 9:00 AM - Oct 22, 2026 at 8:59 AM</td></tr>
              <tr><td>SEA (UTC+8)</td><td>Oct 11, 2026 at 11:00 AM - Oct 22, 2026 at 10:59 AM</td></tr>
              <tr><td>HMT / SEA (UTC+8)</td><td>Oct 11, 2026 at 10:00 AM - Oct 22, 2026 at 9:59 AM</td></tr>
            </table>
        """, "html.parser")

        times = _game8_server_times(soup)

        self.assertEqual(times["HMT"], times["SEA"])
        self.assertEqual("2026-10-11T02:00:00Z", times["HMT"]["startAt"])

    def test_parses_abbreviated_months_and_split_start_end_rows(self):
        soup = BeautifulSoup("""
            <table>
              <tr><td>America (UTC-4)</td></tr>
              <tr><td>Start Date</td><td>Sep. 30, 2026 at 10:00 AM</td></tr>
              <tr><td>End Date</td><td>Oct. 22, 2026 at 8:30 AM</td></tr>
              <tr><td>Europe (UTC+2)</td><td>Sep. 30, 2026 at 10:00 AM - Oct. 22, 2026 at 8:30 AM</td></tr>
            </table>
        """, "html.parser")

        times = _game8_server_times(soup)

        self.assertEqual("2026-09-30T14:00:00Z", times["AMERICA"]["startAt"])
        self.assertEqual("2026-10-22T12:30:00Z", times["AMERICA"]["endAt"])
        self.assertEqual("2026-09-30T08:00:00Z", times["EUROPE"]["startAt"])

    def test_source_health_separates_fetch_failures_from_parser_failures(self):
        import requests

        fetch = _source_health_entry("game8-banner-schedule", requests.HTTPError("503"))
        parse = _source_health_entry("game8-banner-schedule", "No schedule rows parsed", stage="parse")

        self.assertEqual("fetch", fetch["stage"])
        self.assertEqual("parse", parse["stage"])


class PrydwenBannerParsingTest(unittest.TestCase):
    def test_teaser_parser_ignores_related_guides_and_keeps_character_card(self):
        soup = BeautifulSoup("""
            <h2>Upcoming Banners</h2>
            <h3>Patch 3.8 Expected Banners</h3>
            <div><a href="https://www.prydwen.gg/wuthering-waves/characters/lynae">
              <img alt="Lynae" />
            </a></div>
            <h3>Teased banner status</h3>
            <div><a aria-label="Open Lunara" href="https://www.prydwen.gg/wuthering-waves/characters/lunara"></a>
            <a href="https://www.prydwen.gg/wuthering-waves/characters/lunara">
              <img alt="Lunara" />
            </a></div>
            <h2>Summon Rules</h2>
            <h2>Related Pages</h2>
            <a href="https://www.prydwen.gg/wuthering-waves/guides/introduction">Introduction to the game</a>
            <a href="https://www.prydwen.gg/wuthering-waves/guides/reroll">Reroll Guide</a>
            <a href="https://www.prydwen.gg/wuthering-waves/guides/beginner">Beginner Guide</a>
        """, "html.parser")

        snapshots = prydwen_banner_snapshot(soup, soup.get_text(" ", strip=True),
                                            "https://www.prydwen.gg/wuthering-waves/banners", "2026-10-11T00:00:00Z")

        teased = [row for row in snapshots if row["scheduleStatus"] == "TEASED"]
        upcoming = [row for row in snapshots if row["scheduleStatus"] == "UPCOMING"]
        self.assertEqual(["Lunara"], [row["title"] for row in teased])
        self.assertEqual(["Lynae"], [row["title"] for row in upcoming])
        self.assertNotIn("Introduction to the game", " ".join(row["title"] for row in snapshots))

    def test_ambiguous_patch_heading_is_not_mislabeled_and_unscheduled_entry_becomes_next_version_tease(self):
        soup = BeautifulSoup("""
            <h2>Upcoming Banners</h2>
            <h3>Patch 3.6 and Patch 3.7 Expected Banners</h3>
            <div><a href="https://www.prydwen.gg/wuthering-waves/characters/lynae"><img alt="Lynae"/></a></div>
            <h3>Teased banner status</h3>
            <div><a href="https://www.prydwen.gg/wuthering-waves/characters/lily"><img alt="Lily"/></a></div>
            <h2>Summon Rules</h2>
        """, "html.parser")
        snapshots = prydwen_banner_snapshot(soup, soup.get_text(" ", strip=True),
                                            "https://www.prydwen.gg/wuthering-waves/banners", "2026-10-11T00:00:00Z")
        upcoming = next(row for row in snapshots if row["title"] == "Lynae")
        self.assertIsNone(upcoming["version"])
        self.assertEqual("UPCOMING", upcoming["scheduleStatus"])
        tagged_rows = [{**row, "snapshotType": "prydwen"} for row in snapshots]
        tagged_rows = _harmonize_prydwen_rows(tagged_rows, "3.7")
        teaser = next(row for row in tagged_rows if row["title"] == "Lily")
        self.assertEqual("TEASED", teaser["scheduleStatus"])
        self.assertEqual("3.8", teaser["version"])
        tagged = [{**row, "snapshotType": "prydwen"} for row in snapshots]
        tagged.append({"snapshotType": "prydwen", "version": "3.6", "title": "Old", "scheduleStatus": "UPCOMING"})
        kept = _remove_stale_prydwen_rows(tagged, "3.7")
        self.assertFalse(any(row.get("version") == "3.6" for row in kept))
        self.assertTrue(any(row["scheduleStatus"] == "TEASED" for row in kept))

    def test_prydwen_lifecycle_rows_inherit_matching_game8_names_and_server_times(self):
        game8 = {
            "snapshotType": "game8", "title": "Hsin, Chisa, Iuno", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "Game8 Banner Schedule",
            "sourceUrls": ["https://game8.co/current"], "confidence": 0.9,
            "serverTimes": {server: {"startAt": "2026-09-30T01:00:00Z", "endAt": "2026-10-22T01:00:00Z"}
                            for server in ("AMERICA", "EUROPE", "ASIA", "HMT", "SEA")},
        }
        prydwen = {
            "snapshotType": "prydwen", "title": "Hsin, Buling, Taoqi, Chisa, Iuno", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "Prydwen Banner Overview",
            "sourceUrls": ["https://www.prydwen.gg/wuthering-waves/banners"], "confidence": 0.9,
        }
        teased = {"snapshotType": "prydwen", "title": "Lily", "version": None, "phase": None,
                  "scheduleStatus": "TEASED", "sourceUrls": []}
        rows = _harmonize_prydwen_rows([game8, prydwen, teased])
        self.assertEqual(game8["title"], prydwen["title"])
        self.assertEqual(game8["serverTimes"], prydwen["serverTimes"])
        self.assertEqual("Game8 Banner Schedule + Prydwen Banner Overview", prydwen["sourceLabel"])
        self.assertEqual("TEASED", rows[2]["scheduleStatus"])

    def test_wuwabuild_supplies_featured_five_star_names_without_inventing_dates(self):
        wuwabuild = {
            "snapshotType": "wuwabuild", "title": "Hsin, Chisa, Iuno", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "WuWaBuild Banner Schedule", "sourceUrls": [],
        }
        prydwen = {
            "snapshotType": "prydwen", "title": "Hsin, Buling, Taoqi, Chisa, Iuno", "version": "3.7", "phase": 1,
            "scheduleStatus": "CURRENT", "sourceLabel": "Prydwen Banner Overview", "sourceUrls": [],
        }

        _harmonize_prydwen_rows([wuwabuild, prydwen], "3.7")

        self.assertEqual("Hsin, Chisa, Iuno", prydwen["title"])
        self.assertNotIn("startAt", prydwen)
        self.assertFalse(prydwen.get("serverTimes"))

    def test_multi_character_phase_less_prydwen_row_stays_upcoming_for_next_version(self):
        row = {
            "snapshotType": "prydwen", "title": "Lynae, Lucilla, Suoming", "version": None, "phase": None,
            "scheduleStatus": "UPCOMING", "sourceLabel": "Prydwen Banner Overview", "sourceUrls": [],
        }

        _harmonize_prydwen_rows([row], "3.7")

        self.assertEqual("UPCOMING", row["scheduleStatus"])
        self.assertEqual("3.8", row["version"])

    def test_teaser_section_without_character_cards_produces_no_teaser(self):
        soup = BeautifulSoup("""
            <h2>Upcoming Banners</h2>
            <h3>Teased banner status</h3>
            <p>Teased banners stay separate until a phase is confirmed.</p>
            <h2>Related Pages</h2>
            <a href="https://www.prydwen.gg/wuthering-waves/guides/introduction">Introduction to the game</a>
            <a href="https://www.prydwen.gg/wuthering-waves/guides/reroll">Reroll Guide</a>
        """, "html.parser")

        snapshots = prydwen_banner_snapshot(soup, soup.get_text(" ", strip=True),
                                            "https://www.prydwen.gg/wuthering-waves/banners", "2026-10-11T00:00:00Z")

        self.assertIsNone(snapshots)


if __name__ == "__main__":
    unittest.main()
