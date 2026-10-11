import sys
import unittest
from pathlib import Path

from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "data-pipeline"))
from aggregator import _game8_server_times, prydwen_banner_snapshot


class Game8ScheduleParsingTest(unittest.TestCase):
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


class PrydwenBannerParsingTest(unittest.TestCase):
    def test_teaser_parser_ignores_related_guides_and_keeps_character_card(self):
        soup = BeautifulSoup("""
            <h2>Upcoming Banners</h2>
            <h3>Patch 3.8 Expected Banners</h3>
            <div><a href="https://www.prydwen.gg/wuthering-waves/characters/lynae">
              <img alt="Lynae" />
            </a></div>
            <h3>Teased banner status</h3>
            <div><a href="https://www.prydwen.gg/wuthering-waves/characters/lunara">
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
