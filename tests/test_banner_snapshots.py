import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "data-pipeline"))
from aggregator import _game8_server_times, _source_health_entry, game8_banner_snapshot, prydwen_banner_snapshot


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
