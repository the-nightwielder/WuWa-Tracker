#!/usr/bin/env python3
"""WuWa Tracker v1.3 free structured intelligence pipeline.

No paid X API is required. X discovery uses public pages indexed by Google News RSS,
while Reddit's public RSS feed is used for the leak community. All extracted claims are
source-attributed and confidence-scored; leaks never become official automatically.
"""
import hashlib, html, json, os, re
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import quote_plus, urljoin, urlsplit

from security_controls import is_verified_official_source, read_bounded_chunks

import feedparser
import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parent
SOURCES = json.loads((ROOT / "sources.json").read_text())['sources']
OUT = ROOT / "feed.json"
UA = "WuWaTrackerDataBot/1.3 (+https://github.com/)"
TIMEOUT = 20
MAX_SOURCE_RESPONSE_BYTES = 10 * 1024 * 1024
session = requests.Session()
session.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})


def bounded_get(url, **kwargs):
    kwargs.setdefault("timeout", TIMEOUT)
    kwargs["stream"] = True
    response = session.get(url, **kwargs)
    try:
        response.raise_for_status()
        body = read_bounded_chunks(response.iter_content(chunk_size=64 * 1024), MAX_SOURCE_RESPONSE_BYTES)
        return response, body
    finally:
        response.close()

WUWA_TERMS = re.compile(r"wuthering\s+waves|wuwa|resonator|astrite|lunite|convene|tower of adversity|endstate matrix|whimpering wastes|banner|tacet field|phantom", re.I)
GAME_CONTEXT = re.compile(r"wuthering\s+waves|\bwuwa\b", re.I)
REDDIT_TITLE_CONTEXT = re.compile(r"wuthering\s+waves|\bwuwa\b|resonator|banner|convene|\bversion\s*\d|\bv\d+\.\d+", re.I)
LEAK_TERMS = re.compile(r"\b(leak|leaks|leaked|beta|datamine|datamined|test client|stc|subject to change|rumou?r|unconfirmed|sus|drip marketing leak)\b", re.I)
OFFICIAL_TERMS = re.compile(r"official|version|maintenance|special program|special report|resonator reveal|profile|update notice|preview|event notice", re.I)
VERSION_RE = re.compile(r"\b(?:(?:version|ver\.?|v|wuwa)\s*|wuthering\s+waves\s*)(\d+\.\d+)\b", re.I)
DATE_RE = re.compile(r"\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+([0-3]?\d)(?:st|nd|rd|th)?,?\s+(20\d{2})\b", re.I)
DATE_RE_DMY = re.compile(r"\b([0-3]?\d)[/-](1[0-2]|0?[1-9])[/-](20\d{2})\b")
TIME_RE = re.compile(r"\b(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*(?:UTC)?\s*([+-]\d{1,2})?\b", re.I)
PHASE_RE = re.compile(r"\bphase\s*([12])\b", re.I)
BANNER_TERMS = re.compile(r"banner|convene|rerun|rate[- ]up|featured", re.I)
RESONATOR_TERMS = re.compile(r"resonator|5-star|4-star|\bcharacter\b", re.I)
ELEMENTS = ["Aero", "Fusion", "Glacio", "Electro", "Havoc", "Spectro"]
WEAPONS = ["Sword", "Broadblade", "Pistols", "Gauntlets", "Rectifier"]


def clean_text(value):
    if not value:
        return ""
    return re.sub(r"\s+", " ", BeautifulSoup(html.unescape(str(value)), "html.parser").get_text(" ")).strip()


def parse_date(entry):
    for key in ("published", "updated", "created"):
        value = entry.get(key)
        if value:
            try:
                return parsedate_to_datetime(value).astimezone(timezone.utc).isoformat()
            except Exception:
                pass
        parsed = entry.get(key + "_parsed")
        if parsed:
            try:
                return datetime(*parsed[:6], tzinfo=timezone.utc).isoformat()
            except Exception:
                pass
    return datetime.now(timezone.utc).isoformat()


def parse_kuro_datetime(value):
    try:
        parsed = datetime.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone(timedelta(hours=8)))
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def official_json_items(source, payload):
    """Parse recent Kuro MainMenu news JSON into deduplicated, verified items."""
    seen = set()
    now = datetime.now(timezone.utc)
    entries = payload.get("article", []) if isinstance(payload, dict) else []
    if not isinstance(entries, list):
        return
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        article_id = str(entry.get("articleId", ""))
        title = clean_text(entry.get("articleTitle", ""))
        published = parse_kuro_datetime(entry.get("createTime"))
        if not article_id.isdigit() or article_id in seen or not title or not published:
            continue
        published_at = datetime.fromisoformat(published.replace("Z", "+00:00"))
        if abs(now - published_at) > timedelta(days=60):
            continue
        seen.add(article_id)
        url = f"https://wutheringwaves.kurogames.com/en/main/news/detail/{article_id}"
        summary = clean_text(entry.get("articleDesc", ""))
        item = make_item(source, title, url, summary, published,
                         "Wuthering Waves Official Website", url)
        if item:
            yield item
        if len(seen) >= 60:
            break


def official_json(source):
    response, body = bounded_get(source["url"])
    from urllib.parse import urlsplit
    if urlsplit(response.url).hostname != "hw-media-cdn-mingchao.kurogame.com":
        raise ValueError("Official news JSON redirected to an unapproved host")
    payload = json.loads(body.decode(response.encoding or "utf-8"))
    yield from official_json_items(source, payload)


def status_for(source, title, summary, publisher_url=None):
    text = f"{title} {summary}"
    if is_verified_official_source(source, publisher_url):
        return "OFFICIAL", 0.98
    if source['category'] == 'leak-community' or LEAK_TERMS.search(text):
        return "LEAK", 0.52
    return "COMMUNITY", 0.68


def make_item(source, title, url, summary="", published=None, publisher="", publisher_url=None):
    title, summary = clean_text(title), clean_text(summary)
    if not title or not url:
        return None
    if source['kind'] != 'official_json' and not WUWA_TERMS.search(f"{title} {summary}"):
        return None
    if source['kind'] != 'official_json' and not GAME_CONTEXT.search(f"{title} {summary}"):
        return None
    if source['kind'] == 'rss' and not REDDIT_TITLE_CONTEXT.search(title):
        return None
    if 'megathread' in title.lower() and not REDDIT_TITLE_CONTEXT.search(title.replace('megathread', '')):
        return None
    status, confidence = status_for(source, title, summary, publisher_url)
    if status == 'OFFICIAL':
        source_type = 'Official source'
    elif source['id'].startswith('x-'):
        source_type = 'X · indexed by Google News'
    elif source['kind'] == 'rss':
        source_type = 'Reddit'
    else:
        source_type = 'Website'
    return {
        "id": hashlib.sha256(url.encode()).hexdigest()[:20],
        "title": title[:240], "url": url, "summary": summary[:1200],
        "publishedAt": published or datetime.now(timezone.utc).isoformat(),
        "sourceId": source['id'], "sourceCategory": source['category'],
        "sourceType": source_type, "sourceName": clean_text(publisher)[:100] or source['id'],
        "status": status, "confidence": confidence, "priority": source.get('priority', 50)
    }


def google_news(source):
    url = "https://news.google.com/rss/search?q=" + quote_plus(source['query']) + "&hl=en-US&gl=US&ceid=US:en"
    r, body = bounded_get(url)
    feed = feedparser.parse(body)
    for e in feed.entries[:60]:
        source_info = e.get('source') or {}
        publisher = source_info.get('title', '') if isinstance(source_info, dict) else ''
        publisher_url = (source_info.get('href') or source_info.get('url')) if isinstance(source_info, dict) else None
        yield make_item(source, e.get('title'), e.get('link'), e.get('summary'), parse_date(e), publisher, publisher_url)


def rss(source):
    r, body = bounded_get(source['url'])
    feed = feedparser.parse(body)
    for e in feed.entries[:60]:
        yield make_item(source, e.get('title'), e.get('link'), e.get('summary'), parse_date(e))


def html_page(source):
    r, body = bounded_get(source['url'])
    soup = BeautifulSoup(body.decode(r.encoding or 'utf-8', errors='replace'), 'html.parser'); seen = set()
    for a in soup.find_all('a', href=True):
        title = clean_text(a.get_text(' ', strip=True)); href = urljoin(r.url, a['href'])
        if not title or href in seen or len(title) < 8:
            continue
        seen.add(href)
        item = make_item(source, title, href)
        if item:
            yield item


def target_version_from_article(item, next_version):
    article_text = f"{item.get('title', '')} {item.get('summary', '')}"
    headline_versions = [match.group(1) for match in VERSION_RE.finditer(item.get("title", ""))]
    article_versions = headline_versions or [match.group(1) for match in VERSION_RE.finditer(article_text)]
    target_parts = tuple(map(int, next_version.split(".")))
    supported_versions = [value for value in article_versions
                          if tuple(map(int, value.split("."))) >= target_parts]
    return max(supported_versions, key=lambda value: tuple(map(int, value.split(".")))) if supported_versions else None


def iso_utc(year, month, day, hour=0, minute=0):
    try:
        return datetime(year, month, day, hour, minute, tzinfo=timezone.utc).isoformat().replace('+00:00', 'Z')
    except ValueError:
        return None


def date_mentions(text):
    out = []
    months = {m.lower(): i for i, m in enumerate(__import__('calendar').month_name) if m}
    for m in DATE_RE.finditer(text):
        month_word = m.group(0).split()[0].lower().rstrip(',')
        month = next((v for k, v in months.items() if k.startswith(month_word[:3])), None)
        if month:
            value = iso_utc(int(m.group(2)), month, int(m.group(1)))
            if value: out.append((m.start(), m.end(), value, m.group(0)))
    for m in DATE_RE_DMY.finditer(text):
        value = iso_utc(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        if value: out.append((m.start(), m.end(), value, m.group(0)))
    return sorted(out)


def context(text, start, end, radius=180):
    return text[max(0, start-radius):min(len(text), end+radius)]


def claim_kind(title, summary):
    text = f"{title} {summary}"
    if BANNER_TERMS.search(text): return "banner"
    if VERSION_RE.search(text): return "version"
    if re.search(r"tower of adversity|endstate matrix|whimpering wastes", text, re.I): return "endgame"
    if RESONATOR_TERMS.search(text): return "resonator"
    return "event"


def extract_entities(items):
    versions, banners, resonators, events = {}, {}, {}, {}
    for item in items:
        text = f"{item['title']} — {item['summary']}"
        kind = claim_kind(item['title'], item['summary'])
        dates = date_mentions(text)
        vm = VERSION_RE.search(text)
        phase = PHASE_RE.search(text)
        version = vm.group(1) if vm else None
        evidence = {"sourceId": item['sourceId'], "sourceType": item.get('sourceType', 'Website'), "sourceName": item.get('sourceName', item['sourceId']), "url": item['url'], "status": item['status'], "confidence": item['confidence'], "publishedAt": item['publishedAt']}

        if version:
            key = version
            rec = versions.setdefault(key, {"id": f"version-{version}", "version": version, "status": item['status'], "confidence": item['confidence'], "sources": [], "claims": []})
            rec['sources'].append(evidence)
            rec['claims'].append(item['title'])
            if item['status'] == 'OFFICIAL': rec['status'] = 'OFFICIAL'; rec['confidence'] = max(rec['confidence'], 0.98)

        if kind == 'banner':
            bkey = re.sub(r"[^a-z0-9]+", "-", item['title'].lower()).strip('-')[:90]
            rec = banners.setdefault(bkey, {"id": f"banner-{hashlib.sha1(bkey.encode()).hexdigest()[:12]}", "title": item['title'], "version": version, "phase": int(phase.group(1)) if phase else None, "startAt": None, "endAt": None, "status": item['status'], "confidence": item['confidence'], "sources": [], "claims": []})
            rec['sources'].append(evidence); rec['claims'].append(item['title'])
            if dates:
                rec['startAt'] = rec['startAt'] or dates[0][2]
                if len(dates) > 1: rec['endAt'] = dates[-1][2]
            if item['status'] == 'OFFICIAL': rec['status'] = 'OFFICIAL'; rec['confidence'] = max(rec['confidence'], 0.98)

        # Conservative resonator extraction: only use proper-name-like tokens from
        # phrases around "Resonator"/"5-star" and never claim a leak is confirmed.
        if RESONATOR_TERMS.search(text):
            patterns = [r"(?:new|upcoming|leaked|rumored|featured)\s+(?:5[- ]star|4[- ]star)?\s*(?:resonator|character)?\s*[:\-]?\s*([A-Z][A-Za-z]{2,18})",
                        r"\b([A-Z][A-Za-z]{2,18})[,:]?\s+(?:is\s+)?(?:the\s+)?(?:(?:upcoming|new|leaked|rumored)\s+)?(?:version\s+\d+\.\d+\s+)?(?:5[- ]star\s+)?resonator\b",
                        r"\b([A-Z][A-Za-z]{2,18})\s+(?:resonator|banner|rerun)\b"]
            for pat in patterns:
                m = re.search(pat, text)
                if not m: continue
                name = m.group(1)
                if name.lower() in {'wuthering','waves','resonator','character','version','banner','phase'}: continue
                key = name.lower()
                rec = resonators.setdefault(key, {"id": f"resonator-{hashlib.sha1(key.encode()).hexdigest()[:12]}", "name": name, "version": version, "phase": int(phase.group(1)) if phase else None, "element": next((e for e in ELEMENTS if re.search(e, context(text,m.start(),m.end()), re.I)), None), "weapon": next((w for w in WEAPONS if re.search(w, context(text,m.start(),m.end()), re.I)), None), "status": item['status'], "confidence": item['confidence'], "sources": [], "claims": []})
                rec['sources'].append(evidence); rec['claims'].append(item['title'])
                if item['status'] == 'OFFICIAL': rec['status'] = 'OFFICIAL'; rec['confidence'] = max(rec['confidence'], 0.98)
                break

        if dates and kind in ('event','endgame'):
            key = hashlib.sha1((item['url'] + dates[0][2]).encode()).hexdigest()[:16]
            rec = events.setdefault(key, {"id": f"event-{key}", "title": item['title'], "type": 'Endgame' if kind == 'endgame' else 'Event', "version": version, "startAt": dates[0][2], "endAt": dates[-1][2] if len(dates)>1 else None, "status": item['status'], "confidence": item['confidence'], "sourceUrl": item['url'], "sources": [], "claims": []})
            rec['sources'].append(evidence); rec['claims'].append(item['title'])

    return list(versions.values()), list(banners.values()), list(resonators.values()), list(events.values())


def merge_by_entity(records):
    for r in records:
        # Evidence from multiple independent sources increases confidence, but never
        # above 0.98 unless an official source is present.
        unique_sources = {s['sourceId'] for s in r.get('sources', [])}
        if r.get('status') != 'OFFICIAL' and len(unique_sources) >= 3:
            r['confidence'] = min(0.90, r['confidence'] + 0.10)
        elif r.get('status') != 'OFFICIAL' and len(unique_sources) >= 2:
            r['confidence'] = min(0.82, r['confidence'] + 0.06)
        r['sources'] = list({s['url']: s for s in r.get('sources', [])}.values())[:8]
        r['sourceUrls'] = [s['url'] for s in r['sources']]
        r['claims'] = list(dict.fromkeys(r.get('claims', [])))[:8]
    return records



def snapshot_date(value, end_of_day=False):
    try:
        day = datetime.strptime(value.strip(), "%B %d, %Y").replace(tzinfo=timezone.utc)
        if end_of_day:
            day = day.replace(hour=23, minute=59, second=59)
        return day.isoformat().replace("+00:00", "Z")
    except ValueError:
        return None


_FANDOM_IMAGE_CACHE = {}
def fandom_image(name):
    key = name.strip().lower()
    if not key:
        return None
    if key in _FANDOM_IMAGE_CACHE:
        return _FANDOM_IMAGE_CACHE[key]
    image = None
    try:
        response, body = bounded_get("https://wutheringwaves.fandom.com/api.php", params={
            "action": "query", "titles": name, "prop": "pageimages", "format": "json",
            "pithumbsize": 640, "redirects": 1
        })
        pages = json.loads(body.decode(response.encoding or 'utf-8')).get("query", {}).get("pages", {})
        page = next(iter(pages.values()), {})
        image = page.get("thumbnail", {}).get("source")
        if image and not image.startswith("https://"):
            image = None
    except Exception:
        pass
    _FANDOM_IMAGE_CACHE[key] = image
    return image


def banner_snapshot(source):
    try:
        r, body = bounded_get(source["url"])
    except Exception:
        # Game8's archive index can intermittently block automated requests even
        # when its configured evergreen banner overview is available.
        overview_url = source.get("overviewUrl") if source.get("snapshotType") == "game8" else None
        if not overview_url or overview_url == source["url"]:
            raise
        r, body = bounded_get(overview_url)
    soup = BeautifulSoup(body.decode(r.encoding or 'utf-8', errors='replace'), "html.parser")
    image_labels = " ".join(img.get("alt", "") for img in soup.find_all("img"))
    text = clean_text(soup.get_text(" ", strip=True) + " " + image_labels)
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    url = r.url
    if source.get("snapshotType") == "game8":
        return game8_banner_snapshot(source, soup, text, url, now)
    if source.get("snapshotType") == "prydwen":
        return prydwen_banner_snapshot(soup, text, url, now)
    if source.get("snapshotType") == "wuwabuild":
        version = VERSION_RE.search(text)
        resonator_art, weapon_art, art_weapon_urls = [], [], {}
        nodes = soup.find_all(["img", "a"])
        for node in nodes:
            label = clean_text(node.get("alt", "") or node.get("title", "") or node.get_text(" ", strip=True))
            if re.search(r"\bResonator\b", label, re.I):
                candidate = re.sub(r"^.*?\bResonator\s+", "", label, flags=re.I).strip()
                if candidate:
                    resonator_art.append(candidate.split()[-1].strip(" -–:"))
            if re.search(r"\bWeapon\b", label, re.I):
                candidate = re.sub(r"^.*?\bWeapon(?:\s+Absolute\s+Pulsation:)?\s*", "", label, flags=re.I).strip(" .:-")
                parts = candidate.split()
                if len(parts) >= 2 and len(parts) % 2 == 0 and " ".join(parts[:len(parts)//2]).lower() == " ".join(parts[len(parts)//2:]).lower():
                    candidate = " ".join(parts[:len(parts)//2])
                if candidate:
                    weapon_art.append(candidate)
                    image_url = node.get("src") or node.get("data-src")
                    if image_url:
                        art_weapon_urls[candidate] = urljoin(url, image_url)
        art_weapon_map = dict(zip(resonator_art, weapon_art))
        output = []
        for anchor in soup.find_all("a"):
            card = clean_text(anchor.get_text(" ", strip=True))
            vm = VERSION_RE.search(card)
            pm = re.search(r"Phase\s*([12])", card, re.I)
            if not vm or not pm or not re.search(r"Featured weapons?:", card, re.I):
                continue
            # Character names appear immediately after the rarity marker.
            names_match = re.search(r"5\s*★\s*(.+?)\s+(?:First|Second|Third)\s+limited banner", card, re.I)
            if not names_match:
                continue
            names = re.sub(r"\s*\+\s*", ", ", clean_text(names_match.group(1))).replace(" and ", ", ")
            names = re.sub(r"\s*,\s*", ", ", names).strip(" ,")
            weapon_match = re.search(r"Featured weapons?:\s*(.+?)\.(?:Ends|Launch|$)", card, re.I)
            weapon = clean_text(weapon_match.group(1)).rstrip(" .") if weapon_match else None
            date_match = re.search(r"\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\s*(\d{1,2})\s*(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\s*(\d{1,2})\b", card, re.I)
            start = end = None
            if date_match:
                from datetime import date as calendar_date
                months = {m.upper(): i for i, m in enumerate(("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"), 1)}
                year = datetime.now(timezone.utc).year
                sm, sd, em, ed = date_match.groups()
                start_date = calendar_date(year, months[sm.upper()], int(sd))
                end_year = year + (1 if months[em.upper()] < months[sm.upper()] else 0)
                end_date = calendar_date(end_year, months[em.upper()], int(ed))
                start = datetime(start_date.year, start_date.month, start_date.day, tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")
                end = datetime(end_date.year, end_date.month, end_date.day, 23, 59, 59, tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")
            resonators = [name.strip() for name in names.split(",") if name.strip()]
            weapons = [art_weapon_map[name] for name in resonators if name in art_weapon_map]
            weapons += [part.strip() for part in (weapon or "").split(",") if part.strip()]
            weapons = list(dict.fromkeys(weapons))
            schedule_status = None
            if start and end:
                start_instant = datetime.fromisoformat(start.replace("Z", "+00:00"))
                end_instant = datetime.fromisoformat(end.replace("Z", "+00:00"))
                current_instant = datetime.now(timezone.utc)
                schedule_status = "CURRENT" if start_instant <= current_instant <= end_instant else "UPCOMING" if start_instant > current_instant else None
            output.append({
                "title": ", ".join(resonators), "version": vm.group(1), "phase": int(pm.group(1)),
                "startAt": start, "endAt": end, "weapon": ", ".join(weapons),
                "scheduleStatus": schedule_status,
                "resonatorImages": {name: image for name in resonators if (image := fandom_image(name))},
                "weaponImages": {name: image for name in weapons if (image := (fandom_image(name) or art_weapon_urls.get(name)))},
                "status": "COMMUNITY", "confidence": 0.86,
                "sourceLabel": "WuWaBuild banner schedule", "sourceUrls": [url], "snapshotAt": now
            })
        if output:
            return output
        return None
    if source.get("snapshotType") == "current":
        current = re.search(r"Current WuWa banner\s*:\s*([^\.]+?)\.\s*Banner", text, re.I)
        version = VERSION_RE.search(text)
        phase = PHASE_RE.search(text)
        start = end = None
        for row in soup.find_all("tr"):
            cells = [clean_text(cell.get_text(" ", strip=True)) for cell in row.find_all(["td", "th"])]
            if cells and "Live banner phase" in cells[0] and len(cells) > 2:
                phase_match = PHASE_RE.search(" ".join(cells[:2]))
                row_version = VERSION_RE.search(" ".join(cells[:2]))
                version = row_version or version
                phase = phase_match or phase
                dates = re.findall(r"(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2},\s+20\d{2}", cells[2], re.I)
                if len(dates) >= 2:
                    start = snapshot_date(dates[0])
                    end = snapshot_date(dates[1], True)
                break
        title = clean_text(current.group(1)).rstrip(".") if current else ""
        if not title:
            return None
        weapon_match = re.search(r"(?:featured|signature)\s+weapon(?:\s+banner)?\s*(?:is|:|—|-)\s*([A-Z][A-Za-z0-9'’ -]{2,40})", text, re.I)
        weapon = clean_text(weapon_match.group(1)).rstrip(" .") if weapon_match else None
        resonators = [name.strip() for name in re.split(r",|\band\b", title) if name.strip()]
        return {
            "title": title, "version": version.group(1) if version else None,
            "phase": int(phase.group(1)) if phase else None, "startAt": start, "endAt": end,
            "weapon": weapon, "status": "COMMUNITY", "confidence": 0.85,
            "resonatorImages": {name: image for name in resonators if (image := fandom_image(name))},
            "weaponImages": {weapon: image} if weapon and (image := fandom_image(weapon)) else {},
            "sourceLabel": "WuWa Banners", "sourceUrls": [url], "snapshotAt": now
        }
    heading = next((clean_text(h.get_text(" ", strip=True)) for h in soup.find_all(["h1", "h2", "h3"]) if "countdown" in h.get_text(" ", strip=True).lower()), "")
    match = re.search(r"([A-Z][A-Za-z]+(?:,\s*[A-Z][A-Za-z]+)*(?:,?\s+and\s+[A-Z][A-Za-z]+)?)\s+Banner Countdown", heading, re.I)
    release = re.search(r"(?:set to release|release|banner starts?)\b.+?\bon\s+([A-Z][a-z]+\s+\d{1,2},\s+20\d{2})", text, re.I)
    title = clean_text(match.group(1)).replace(" and ", ", ") if match else ""
    if not title:
        return None
    version = VERSION_RE.search(text)
    phase_match = re.search(r"Phase\s*(?:II|2|I|1)", text, re.I)
    phase = 2 if phase_match and phase_match.group(0).lower().endswith(("ii", "2")) else (1 if phase_match else None)
    start = snapshot_date(release.group(1)) if release else None
    resonators = [name.strip() for name in re.split(r",|\band\b", title) if name.strip()]
    weapon_match = re.search(r"(?:featured|signature)\s+weapon(?:\s+banner)?\s*(?:is|:|—|-)\s*([A-Z][A-Za-z0-9'’ -]{2,40})", text, re.I)
    weapon = clean_text(weapon_match.group(1)).rstrip(" .") if weapon_match else None
    banner_art = [urljoin(url, img.get("src") or img.get("data-src") or "") for img in soup.find_all("img")
                  if "Phase 2 Banner" in (img.get("alt", "") + " " + img.get("title", "")) and (img.get("src") or img.get("data-src"))]
    return {
        "title": title, "version": version.group(1) if version else None,
        "phase": phase, "startAt": start, "endAt": None, "weapon": weapon,
        "resonatorImages": {name: image for name in resonators if (image := fandom_image(name))},
        "weaponImages": {weapon: image} if weapon and (image := fandom_image(weapon)) else {},
        "bannerArtwork": banner_art,
        "status": "COMMUNITY", "confidence": 0.78,
        "sourceLabel": "GenGamer Countdown", "sourceUrls": [url], "snapshotAt": now
    }


def timeline_weapon_details(source):
    """Read labeled weapon-banner artwork from the public WuWa Tracker timeline."""
    r, body = bounded_get(source["url"])
    soup = BeautifulSoup(body.decode(r.encoding or 'utf-8', errors='replace'), "html.parser")
    details, labels = {}, []
    for node in soup.find_all(["img", "a", "figure", "div"]):
        for value in (node.get("alt", ""), node.get("title", ""), node.get("aria-label", "")):
            if value:
                labels.append(clean_text(value))
    labels += [clean_text(s.get_text(" ", strip=True)) for s in soup.find_all("script") if s.string]
    for label in labels:
        match = re.search(r"([A-Z][A-Za-z0-9'’ -]{1,60}?)\s*[-–:]\s*([A-Z][A-Za-z0-9'’ -]{1,30}?)\s+Weapon Banner", label, re.I)
        if match:
            weapon, character = (clean_text(x).strip(" -–:") for x in match.groups())
            if weapon and character and character.lower() not in {"weapon", "banner"}:
                details[character] = weapon
    return details


GAME8_SERVER_ROWS = {
    "america": ("AMERICA", -5),
    "europe": ("EUROPE", 1),
    "asia": ("ASIA", 9),
    "hmt": ("HMT", 8),
    "sea": ("SEA", 8),
}
GAME8_DATE_TIME_RE = re.compile(
    r"\b((?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
    r"Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\.?"
    r"\s+\d{1,2},?\s+20\d{2})\s*(?:at\s*)?(\d{1,2}(?::\d{2})?\s*[AP]M)\b", re.I)
GAME8_DATE_RANGE_RE = re.compile(
    r"\b((?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
    r"Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\.?"
    r"\s+\d{1,2},?\s+20\d{2})\s*(?:to|[-–—])\s*"
    r"((?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
    r"Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\.?"
    r"\s+\d{1,2},?\s+20\d{2})", re.I)
GAME8_COUNTDOWN_RE = re.compile(
    r"(\d+)\s*(days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b", re.I)


def _game8_parse_date(value):
    value = re.sub(r",(?=\s*20\d{2})", "", value)
    value = re.sub(r"\b(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\.(?=\s)", r"\1", value, flags=re.I)
    value = re.sub(r"\s+", " ", value).strip()
    for date_format in ("%B %d %Y", "%b %d %Y"):
        try:
            return datetime.strptime(value, date_format).date()
        except ValueError:
            continue
    return None


def _game8_date_span(text):
    match = GAME8_DATE_RANGE_RE.search(text)
    if not match:
        return None
    start, end = (_game8_parse_date(value) for value in match.groups())
    if not start or not end or end <= start:
        return None
    return start, end


def _game8_countdown_seconds(text):
    parts = list(GAME8_COUNTDOWN_RE.finditer(text))
    if not parts:
        return None
    residue = GAME8_COUNTDOWN_RE.sub(" ", text).lower()
    # Game8 renders timers as bare values and as labeled countdown widgets.
    residue = re.sub(r"\b(?:countdown|time|remaining|left|in|expires|ends?|server|america|europe|asia|hmt|sea|utc|gmt|and)\b", " ", residue)
    residue = re.sub(r"[()./:,·|;\-–—+\s]", "", residue)
    if residue:
        return None
    seconds = 0
    for part in parts:
        value, unit = int(part.group(1)), part.group(2).lower()
        if unit.startswith("d"):
            seconds += value * 86400
        elif unit.startswith("h"):
            seconds += value * 3600
        elif unit.startswith("m"):
            seconds += value * 60
        else:
            seconds += value
    return seconds if seconds > 0 else None


def _game8_timer_text(text):
    return re.split(r"\b(?:phase\s*[12]|featured\s+characters?|character\s+banners?|next\s+upcoming\s+banners?)\b", text, maxsplit=1, flags=re.I)[0]


def _game8_utc_datetime(value):
    if isinstance(value, str):
        value = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _game8_iso(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _game8_datetime(match, offset_hours):
    date_text, time_text = match.groups()
    normalized = re.sub(r",(?=\s*20\d{2})", "", date_text)
    normalized = re.sub(r"\b(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\.(?=\s)", r"\1", normalized, flags=re.I)
    normalized = re.sub(r"\s+", " ", normalized).strip()
    time_text = re.sub(r"\s+", "", time_text).upper()
    parsed = None
    for date_format in ("%B %d %Y", "%b %d %Y"):
        for time_format in ("%I:%M%p", "%I%p"):
            try:
                parsed = datetime.strptime(f"{normalized} {time_text}", f"{date_format} {time_format}")
                break
            except ValueError:
                continue
        if parsed:
            break
    if not parsed:
        return None
    return parsed.replace(tzinfo=timezone(timedelta(hours=offset_hours))).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _bounded_game8_get(url):
    """Follow only same-site Game8 redirects while scraping the public guide."""
    for _ in range(4):
        if urlsplit(url).hostname not in {"game8.co", "www.game8.co"}:
            raise ValueError("Refusing to fetch a banner link outside the Game8 domain")
        response, body = bounded_get(url, allow_redirects=False)
        if response.is_redirect:
            location = response.headers.get("Location")
            if not location:
                raise ValueError("Game8 returned a redirect without a destination")
            url = urljoin(response.url, location)
            continue
        return response, body
    raise ValueError("Too many redirects while reading the Game8 banner guide")


def _game8_server_times(soup, now=None, calendar_text=None):
    observed_at = _game8_utc_datetime(now or datetime.now(timezone.utc))
    times = {}
    combined_hmt_sea = None
    page_text = clean_text(soup.get_text(" ", strip=True))
    page_date_span = _game8_date_span(calendar_text or page_text)
    rows = soup.find_all("tr")
    for row_index, row in enumerate(rows):
        cells = [clean_text(cell.get_text(" ", strip=True)) for cell in row.find_all(["td", "th"])]
        if not cells:
            continue
        region_text = cells[0].lower()
        combined = re.search(r"\bhmt\s*/\s*sea\b", region_text)
        key = next((value for name, (value, _) in GAME8_SERVER_ROWS.items() if re.search(rf"\b{name}\b", region_text)), None)
        if not key:
            continue
        offset = next(offset for name, (value, offset) in GAME8_SERVER_ROWS.items() if value == key and re.search(rf"\b{name}\b", region_text))
        # Prefer the source's explicit UTC offset when available (it may change
        # with daylight saving time); otherwise use the documented server offset.
        offset_match = re.search(r"\b(?:UTC|GMT)\s*([+-])\s*(\d{1,2})(?::(\d{2}))?", " ".join(cells[:2]), re.I)
        if offset_match:
            minutes = int(offset_match.group(2)) * 60 + int(offset_match.group(3) or 0)
            offset = (1 if offset_match.group(1) == "+" else -1) * minutes / 60
        # Some Game8 tables put the region in one row, then show start and end
        # as separate date rows below it. Collect until the next region label.
        timing_cells = list(cells[1:])
        for following in rows[row_index + 1:]:
            following_cells = [clean_text(cell.get_text(" ", strip=True)) for cell in following.find_all(["td", "th"])]
            if not following_cells:
                continue
            following_region = " ".join(following_cells[:2]).lower()
            if any(re.search(rf"\b{name}\b", following_region) for name in GAME8_SERVER_ROWS):
                break
            timing_cells.extend(following_cells)
        matches = list(GAME8_DATE_TIME_RE.finditer(" ".join(timing_cells)))
        values = [_game8_datetime(match, offset) for match in matches]
        values = [value for value in values if value]
        if len(values) >= 2:
            record = {"startAt": values[0], "endAt": values[1]}
            if combined:
                combined_hmt_sea = record
            else:
                times[key] = record
            continue

        # The evergreen Game8 overview uses a shared calendar-date range and a
        # live remaining-time countdown for each server instead of date/time strings.
        table = row.find_parent("table")
        date_span = _game8_date_span(clean_text(table.get_text(" ", strip=True))) if table else None
        date_span = date_span or page_date_span

        # Check if Game8 uses the newer data-date attribute for the countdown timer
        timer_div = row.find("div", class_="js-saola-timer")
        if date_span and timer_div and timer_div.has_attr("data-date"):
            start_date, end_date = date_span
            end_at = datetime.fromtimestamp(int(timer_div["data-date"]) / 1000.0, tz=timezone.utc)
            start_at = end_at - timedelta(days=(end_date - start_date).days)
            record = {"startAt": _game8_iso(start_at), "endAt": _game8_iso(end_at)}
            if combined:
                combined_hmt_sea = record
            else:
                times[key] = record
            continue

        remaining = _game8_countdown_seconds(" ".join(cells[1:]))
        if date_span and remaining is not None:
            start_date, end_date = date_span
            end_at = observed_at + timedelta(seconds=remaining)
            # Preserve the timer's server-local clock across the published day span.
            start_at = end_at - timedelta(days=(end_date - start_date).days)
            record = {"startAt": _game8_iso(start_at), "endAt": _game8_iso(end_at)}
            if combined:
                combined_hmt_sea = record
            else:
                times[key] = record
    if times or combined_hmt_sea:
        shared_hmt_sea = combined_hmt_sea or times.get("HMT") or times.get("SEA")
        if shared_hmt_sea:
            # They remain independent UI choices while intentionally sharing
            # the schedule specified for HMT/SEA.
            times["HMT"] = dict(shared_hmt_sea)
            times["SEA"] = dict(shared_hmt_sea)
        return times
    # Some Game8 layouts render the schedule as cards instead of table rows.
    flat = clean_text(calendar_text) if calendar_text else page_text
    regions = list(re.finditer(r"\b(America|Europe|Asia|HMT\s*/\s*SEA|HMT|SEA)\b", flat, re.I))
    for index, region in enumerate(regions):
        region_name = re.sub(r"\s*/\s*", " / ", region.group(1)).lower()
        key, offset = ("HMT", 8) if region_name in {"hmt / sea", "hmt"} else ("SEA", 8) if region_name == "sea" else GAME8_SERVER_ROWS[region_name]
        stop = regions[index + 1].start() if index + 1 < len(regions) else min(len(flat), region.end() + 420)
        segment = flat[region.start():stop]
        matches = list(GAME8_DATE_TIME_RE.finditer(segment))
        values = [_game8_datetime(match, offset) for match in matches]
        values = [value for value in values if value]
        if len(values) >= 2:
            record = {"startAt": values[0], "endAt": values[1]}
            times[key] = record
            continue
        remaining = _game8_countdown_seconds(_game8_timer_text(segment))
        if page_date_span and remaining is not None:
            start_date, end_date = page_date_span
            end_at = observed_at + timedelta(seconds=remaining)
            start_at = end_at - timedelta(days=(end_date - start_date).days)
            times[key] = {"startAt": _game8_iso(start_at), "endAt": _game8_iso(end_at)}
    # Game8 commonly displays HMT and SEA as one timer card, which can be
    # separated into two labels by its responsive markup before this text pass.
    if "HMT" not in times and "SEA" not in times:
        combined_match = re.search(r"\bHMT\s*/\s*SEA\b", flat, re.I)
        if combined_match and page_date_span:
            next_region = re.search(r"\b(?:America|Europe|Asia)\b", flat[combined_match.end():], re.I)
            stop = combined_match.end() + next_region.start() if next_region else min(len(flat), combined_match.end() + 420)
            remaining = _game8_countdown_seconds(_game8_timer_text(flat[combined_match.start():stop]))
            if remaining is not None:
                start_date, end_date = page_date_span
                end_at = observed_at + timedelta(seconds=remaining)
                start_at = end_at - timedelta(days=(end_date - start_date).days)
                times["HMT"] = {"startAt": _game8_iso(start_at), "endAt": _game8_iso(end_at)}
                times["SEA"] = dict(times["HMT"])
    shared_hmt_sea = times.get("HMT") or times.get("SEA")
    if shared_hmt_sea:
        times["HMT"] = dict(shared_hmt_sea)
        times["SEA"] = dict(shared_hmt_sea)
    return times


def _game8_banner_article(url):
    r, body = _bounded_game8_get(url)
    soup = BeautifulSoup(body.decode(r.encoding or "utf-8", errors="replace"), "html.parser")
    text = clean_text(soup.get_text(" ", strip=True))
    heading = clean_text(soup.find("h1").get_text(" ", strip=True)) if soup.find("h1") else ""
    title_match = re.match(r"(.+?)\s+(?:Rerun\s+)?Banner\s+Release\s+Date", heading, re.I)
    name = clean_text(title_match.group(1)).replace(" Rerun", "") if title_match else ""
    run_match = re.search(r"Latest Run\s*Version\s*(\d+\.\d+)", text, re.I)
    version_match = VERSION_RE.search(heading) or VERSION_RE.search(text)
    version = run_match.group(1) if run_match else version_match.group(1) if version_match else None
    phase_match = re.search(r"(?:first|1st)\s+phase.{0,100}?Version\s+\d+\.\d+", text, re.I)
    phase = 1 if phase_match else None
    if not phase:
        phase_match = re.search(r"(?:second|2nd)\s+phase.{0,100}?Version\s+\d+\.\d+", text, re.I)
        if phase_match:
            phase = 2
    if not phase:
        phase_match = re.search(r"Phase\s*(II|2|I|1)", text, re.I)
        if phase_match:
            phase = 2 if phase_match.group(1).upper() in {"II", "2"} else 1
    server_times = _game8_server_times(soup)
    if not name or not version or phase not in (1, 2) or not server_times:
        return None
    return {"name": name, "version": version, "phase": phase, "serverTimes": server_times, "url": r.url}


def game8_banner_snapshot(source, soup, text, url, now):
    """Discover the latest Game8 banner guide and its current/upcoming character pages."""
    guide_links = [(clean_text(anchor.get_text(" ", strip=True)), urljoin(url, anchor["href"]))
                   for anchor in soup.find_all("a", href=True)
                   if urlsplit(urljoin(url, anchor["href"])).hostname in {"game8.co", "www.game8.co"}]
    maintained = next((href for label, href in guide_links
                       if (re.search(r"all\s+banners?\b.*\bcurrent\b.*\b(?:next|upcoming)\b", label, re.I)
                           or re.search(r"all\s+current\s+and\s+(?:next|upcoming)\s+banners?", label, re.I))), None)
    maintained = maintained or source.get("overviewUrl")
    if not maintained:
        version_guides = []
        for label, href in guide_links:
            version_match = VERSION_RE.search(label)
            if version_match and re.search(r"banner", label, re.I):
                version_guides.append((tuple(map(int, version_match.group(1).split("."))), href))
        maintained = max(version_guides, default=(None, None), key=lambda item: item[0])[1]
    guide_url = maintained
    if guide_url and guide_url != url:
        try:
            response, body = _bounded_game8_get(guide_url)
        except Exception:
            fallback_url = source.get("overviewUrl")
            if not fallback_url or fallback_url == guide_url:
                raise
            response, body = _bounded_game8_get(fallback_url)
        url = response.url
        soup = BeautifulSoup(body.decode(response.encoding or "utf-8", errors="replace"), "html.parser")
        text = clean_text(soup.get_text(" ", strip=True))

    overview = _game8_overview_snapshots(soup, url, now)
    if overview:
        return overview
    # The evergreen Game8 guide links to character articles using only the
    # character name (for example, "Hsin"). Requiring "banner" or "rerun" in
    # the link label/path silently discarded every useful page, which left the
    # app with Prydwen's mixed 5-star/4-star list and no regional times.
    article_urls = []
    headings = soup.find_all("h2")
    relevant_sections = []
    for heading in headings:
        label = clean_text(heading.get_text(" ", strip=True))
        if re.search(r"\b(?:(?:current|active)|(?:next|upcoming)(?:\s+upcoming)?|upcoming\s+next)\s+(?:character\s+)?banners?\b", label, re.I):
            relevant_sections.append(heading)
    for heading in relevant_sections:
        for anchor in heading.find_all_next("a", href=True):
            if anchor.find_previous("h2") is not heading:
                break
            href = urljoin(url, anchor["href"])
            parts = urlsplit(href)
            if (parts.hostname not in {"game8.co", "www.game8.co"}
                    or not re.search(r"/games/Wuthering-Waves/archives/\d+", parts.path, re.I)):
                continue
            article_urls.append(href.split("?", 1)[0])
    # Some archive layouts omit the section headings. Keep the older keyword
    # discovery as a bounded fallback for those pages.
    if not article_urls:
        for anchor in soup.find_all("a", href=True):
            label = clean_text(anchor.get_text(" ", strip=True) or anchor.get("title", ""))
            href = urljoin(url, anchor["href"])
            parts = urlsplit(href)
            if (parts.hostname not in {"game8.co", "www.game8.co"}
                    or not re.search(r"/games/Wuthering-Waves/archives/\d+", parts.path, re.I)
                    or not re.search(r"banner|convene|rerun", f"{label} {parts.path}", re.I)):
                continue
            article_urls.append(href.split("?", 1)[0])
    article_urls = list(dict.fromkeys(article_urls))[:12]
    grouped = {}
    for article_url in article_urls:
        try:
            record = _game8_banner_article(article_url)
        except Exception:
            continue
        if not record:
            continue
        key = (record["version"], record["phase"])
        group = grouped.setdefault(key, {"names": [], "serverTimes": record["serverTimes"], "urls": []})
        if record["name"].lower() not in {name.lower() for name in group["names"]}:
            group["names"].append(record["name"])
        group["urls"].append(record["url"])
        # Prefer a complete server set if one linked character page has it.
        if len(record["serverTimes"]) > len(group["serverTimes"]):
            group["serverTimes"] = record["serverTimes"]
    output = []
    current = datetime.now(timezone.utc)
    for (version, phase), group in grouped.items():
        times = group["serverTimes"]
        sea = times.get("SEA", {})
        start, end = sea.get("startAt"), sea.get("endAt")
        if not start or not end:
            continue
        end_instant = datetime.fromisoformat(end.replace("Z", "+00:00"))
        if end_instant < current - timedelta(days=1):
            continue
        start_instant = datetime.fromisoformat(start.replace("Z", "+00:00"))
        output.append({
            "title": ", ".join(group["names"]), "version": version, "phase": phase,
            "startAt": start, "endAt": end, "serverTimes": times,
            "status": "COMMUNITY", "scheduleStatus": "CURRENT" if start_instant <= current <= end_instant else "UPCOMING",
            "confidence": 0.98, "sourceLabel": "Game8 Banner Schedule",
            "sourceUrls": list(dict.fromkeys(group["urls"])), "snapshotAt": now,
        })
    return output or None


def _game8_section(headings, predicate):
    heading = next((item for item in headings if predicate(clean_text(item.get_text(" ", strip=True)))), None)
    if not heading:
        return None, []
    section = []
    for node in heading.find_all_next(["h2", "h3", "h4", "table"]):
        if node.name == "h2":
            break
        section.append(node)
    return heading, section


def _game8_section_text(heading, stop_heading=None):
    """Collect section text without assuming Game8 uses tables for the content."""
    parts = []
    for node in heading.find_all_next(string=True):
        if stop_heading is not None and stop_heading in node.parents:
            break
        parent_h2 = node.find_parent("h2")
        if parent_h2 is not None and parent_h2 is not heading:
            break
        value = str(node).strip()
        if value:
            parts.append(value)
    return clean_text(" ".join(parts))


def _game8_text_before(soup, stop_node):
    parts = []
    for node in soup.descendants:
        if node is stop_node:
            break
        if not getattr(node, "name", None):
            value = str(node).strip()
            if value:
                parts.append(value)
    return clean_text(" ".join(parts))


def _game8_featured_characters(section):
    # Game8's current-phase table labels the featured 5-star row explicitly.
    # Keep this path first so the adjacent 4-star rate-up row is never mixed in.
    for table in (node for node in section if node.name == "table"):
        table_text = clean_text(table.get_text(" ", strip=True))
        if "Featured Characters" not in table_text or "5★ Limited Rate-Up" not in table_text:
            continue
        for row in table.find_all("tr"):
            cells = row.find_all(["td", "th"])
            if len(cells) < 2 or "5★ Limited Rate-Up" not in clean_text(row.get_text(" ", strip=True)):
                continue
            markup = str(cells[-1])
            markup = re.split(r"4\s*★\s*Rate-Up", markup, maxsplit=1, flags=re.I)[0]
            featured = BeautifulSoup(markup, "html.parser")
            names = []
            for anchor in featured.find_all("a"):
                image = anchor.find("img")
                label = clean_text((image.get("alt", "") if image else "")
                                   or anchor.get("aria-label", "") or anchor.get("title", "")
                                   or anchor.get_text(" ", strip=True))
                label = re.sub(r"^Wuthering\s+Waves\s*[-–:]\s*", "", label, flags=re.I)
                label = re.sub(r"^Open\s+", "", label, flags=re.I).strip()
                banner_name = re.match(r"([\w'’.-]+)\s+banner\b", label, re.I)
                if banner_name:
                    label = banner_name.group(1)
                anchor_text = clean_text(anchor.get_text(" ", strip=True))
                if anchor_text and not re.search(r"(?:banner|weapon|signature|icon)", anchor_text, re.I):
                    anchor_text = re.sub(r"^Open\s+", "", anchor_text, flags=re.I).strip()
                    if label.lower() in {"character", "resonator", "wuthering waves"} or not label:
                        label = anchor_text
                if (2 <= len(label) <= 40 and not re.search(r"banner|weapon|signature|icon|limited|rate-up", label, re.I)
                        and label.lower() not in {name.lower() for name in names}):
                    names.append(label)
            if names:
                return names

    # The evergreen overview uses a different layout for the next phase: its
    # phase table contains one linked card per featured character, without a
    # "5★ Limited Rate-Up" label. Find that first phase table, and stop before
    # the following heading (which commonly introduces signature weapons).
    phase_heading = next((node for node in section
                          if node.name in {"h3", "h4"}
                          and re.search(r"\bphase\s*(?:[12]|i{1,2})\b|\bwith\s+multiple\s+reruns\b",
                                        clean_text(node.get_text(" ", strip=True)), re.I)), None)
    if phase_heading:
        phase_table = None
        for node in phase_heading.find_all_next(["h2", "h3", "h4", "table"]):
            if node.name in {"h2", "h3", "h4"}:
                break
            if node.name == "table":
                phase_table = node
                break
        if phase_table:
            names = []
            for anchor in phase_table.find_all("a", href=True):
                image = anchor.find("img")
                label = clean_text((image.get("alt", "") if image else "")
                                   or anchor.get("aria-label", "") or anchor.get("title", "")
                                   or anchor.get_text(" ", strip=True))
                label = re.sub(r"^Wuthering\s+Waves\s*[-–:]\s*", "", label, flags=re.I)
                label = re.sub(r"^(?:open|view)\s+", "", label, flags=re.I).strip()
                # Game8 card labels look like "Lynae Banner - ... Lynae Rerun
                # Banner". The character name is the leading text before Banner.
                match = re.match(r"([\w'’.-]+)\s+banner\b", label, re.I)
                if not match:
                    anchor_text = clean_text(anchor.get_text(" ", strip=True))
                    match = re.match(r"([\w'’.-]+)\s+banner\b", anchor_text, re.I)
                name = match.group(1).strip() if match else ""
                if (2 <= len(name) <= 32 and name.lower() not in {item.lower() for item in names}
                        and not re.search(r"weapon|signature|banner|rate-up", name, re.I)):
                    names.append(name)
            if names:
                return names
    return []


def _game8_overview_snapshots(soup, url, now):
    """Parse the evergreen Game8 overview's current timer and next phase dates."""
    headings = soup.find_all(["h2", "h3", "h4"])
    current_heading, current_section = _game8_section(
        headings, lambda label: bool(re.search(r"\b(?:current|active)\s+(?:character\s+)?banners?\b", label, re.I)))
    upcoming_heading, upcoming_section = _game8_section(
        headings, lambda label: bool(re.search(r"\b(?:(?:next|upcoming)(?:\s+upcoming)?|upcoming\s+next)\s+(?:character\s+)?banners?\b", label, re.I)))
    if not current_heading or not upcoming_heading:
        return None

    current_text = _game8_section_text(current_heading, upcoming_heading)
    upcoming_text = _game8_section_text(upcoming_heading)
    server_times = _game8_server_times(soup, now, current_text)
    required_servers = {"AMERICA", "EUROPE", "ASIA", "HMT", "SEA"}
    if not required_servers.issubset(server_times):
        return None
    current_dates = _game8_date_span(current_text)
    upcoming_dates = _game8_date_span(upcoming_text)
    if not current_dates or not upcoming_dates:
        return None

    version_candidates = list(VERSION_RE.finditer(_game8_text_before(soup, current_heading) + " " + current_text))
    version_match = version_candidates[-1] if version_candidates else None
    if not version_match:
        return None
    version = version_match.group(1)
    current_phase_heading = next((node for node in current_section
                                  if node.name in {"h3", "h4"} and re.search(r"\bphase\s*[1i]\b", node.get_text(" ", strip=True), re.I)), None)
    upcoming_phase_match = re.search(r"\bphase\s*(1|2|i{1,2})\s+banners?\b", upcoming_text, re.I)
    if not current_phase_heading:
        return None
    current_phase_match = re.search(r"\bphase\s*([12])\b", current_phase_heading.get_text(" ", strip=True), re.I)
    current_phase = int(current_phase_match.group(1)) if current_phase_match else 1
    upcoming_phase_value = upcoming_phase_match.group(1).upper() if upcoming_phase_match else None
    upcoming_phase = (2 if upcoming_phase_value in {"2", "II"} else 1 if upcoming_phase_value in {"1", "I"}
                      else current_phase + 1)
    current_names = _game8_featured_characters(current_section)
    upcoming_names = _game8_featured_characters(upcoming_section)
    if not current_names or not upcoming_names:
        return None

    current_span_days = (current_dates[1] - current_dates[0]).days
    upcoming_span_days = (upcoming_dates[1] - upcoming_dates[0]).days
    if current_span_days <= 0 or upcoming_span_days <= 0:
        return None

    current_server_times, upcoming_server_times = {}, {}
    for server, timing in server_times.items():
        current_end = _game8_utc_datetime(timing["endAt"])
        current_start = current_end - timedelta(days=current_span_days)
        next_start = current_end
        next_end = next_start + timedelta(days=upcoming_span_days)
        current_server_times[server] = {"startAt": _game8_iso(current_start), "endAt": _game8_iso(current_end)}
        upcoming_server_times[server] = {"startAt": _game8_iso(next_start), "endAt": _game8_iso(next_end)}

    common = {
        "version": version,
        "status": "COMMUNITY",
        "confidence": 0.9,
        "sourceLabel": "Game8 Banner Schedule",
        "sourceUrls": [url],
        "snapshotAt": _game8_iso(_game8_utc_datetime(now)),
        "timingBasis": "Calculated from Game8 per-server countdowns and published calendar-day spans; the next phase starts at the current phase transition.",
    }
    return [
        {**common, "title": ", ".join(current_names), "phase": current_phase,
         "startAt": current_server_times["SEA"]["startAt"], "endAt": current_server_times["SEA"]["endAt"],
         "serverTimes": current_server_times, "scheduleStatus": "CURRENT"},
        {**common, "title": ", ".join(upcoming_names), "phase": upcoming_phase,
         "startAt": upcoming_server_times["SEA"]["startAt"], "endAt": upcoming_server_times["SEA"]["endAt"],
         "serverTimes": upcoming_server_times, "scheduleStatus": "UPCOMING"},
    ]


def _source_health_entry(source_id, error, stage=None):
    if stage is None:
        stage = "fetch" if isinstance(error, requests.exceptions.RequestException) else "process"
    return {"sourceId": source_id, "stage": stage, "error": str(error)[:300]}


def _remove_stale_prydwen_rows(snapshots, live_version):
    if not live_version:
        return snapshots
    live = tuple(map(int, live_version.split(".")))
    return [row for row in snapshots if not (
        row.get("snapshotType") == "prydwen" and row.get("version")
        and tuple(map(int, row["version"].split("."))) < live
    )]


def _harmonize_prydwen_rows(snapshots, live_version=None):
    """Use schedule sources for featured 5-star names and Game8 for regional times."""
    required_servers = {"AMERICA", "EUROPE", "ASIA", "HMT", "SEA"}

    def has_complete_server_schedule(row):
        times = row.get("serverTimes")
        return isinstance(times, dict) and all(
            isinstance(times.get(server), dict)
            and times[server].get("startAt")
            and times[server].get("endAt")
            for server in required_servers
        )

    schedules = [
        row for row in snapshots
        if row.get("snapshotType") == "game8"
        and has_complete_server_schedule(row)
    ]
    name_sources = [row for row in snapshots if row.get("snapshotType") in {"game8", "wuwabuild"}]

    # WuWaBuild has useful featured-character and weapon data but only publishes
    # calendar dates. Attach Game8's complete regional schedule by version/phase
    # so the app does not have to display an unspecific UTC date as a banner time.
    for row in snapshots:
        if row.get("snapshotType") != "wuwabuild" or row.get("serverTimes"):
            continue
        candidates = [candidate for candidate in schedules
                      if candidate.get("version") == row.get("version")
                      and candidate.get("phase") == row.get("phase")
                      and candidate.get("scheduleStatus") == row.get("scheduleStatus")]
        if len(candidates) == 1:
            timed = candidates[0]
            row.update({key: timed[key] for key in ("startAt", "endAt", "serverTimes", "timingBasis") if key in timed})
            row["sourceLabel"] = f"{row.get('sourceLabel', 'WuWaBuild banner schedule')} + Game8 Banner Schedule"
            row["sourceUrls"] = list(dict.fromkeys(row.get("sourceUrls", []) + timed.get("sourceUrls", [])))

    for row in snapshots:
        if row.get("snapshotType") != "prydwen":
            continue
        if row.get("scheduleStatus") == "TEASED":
            if not row.get("version") and live_version:
                major, minor = map(int, live_version.split("."))
                row["version"] = f"{major}.{minor + 1}"
            continue
        if row.get("scheduleStatus") == "UPCOMING" and row.get("phase") is None and not row.get("serverTimes"):
            # A singleton without a phase is a tease; a multi-character group
            # remains an upcoming lineup. Ambiguous headings target next version.
            names_in_row = [name.strip() for name in re.split(r",|\band\b", row.get("title", "")) if name.strip()]
            if len(names_in_row) == 1:
                row["scheduleStatus"] = "TEASED"
            if not row.get("version") and live_version:
                major, minor = map(int, live_version.split("."))
                row["version"] = f"{major}.{minor + 1}"
            if row.get("scheduleStatus") == "TEASED":
                continue
        names = {name.strip().casefold() for name in re.split(r",|\band\b", row.get("title", "")) if name.strip()}
        candidates = [candidate for candidate in name_sources
                      if (candidate.get("snapshotType") == "wuwabuild" or candidate.get("scheduleStatus") == row.get("scheduleStatus"))
                      and candidate.get("version") == row.get("version")
                      and candidate.get("phase") == row.get("phase")]
        best = max(candidates, key=lambda candidate: len(names & {
            name.strip().casefold() for name in re.split(r",|\band\b", candidate.get("title", "")) if name.strip()
        }), default=None)
        if best is None:
            continue
        matched = names & {name.strip().casefold() for name in re.split(r",|\band\b", best.get("title", "")) if name.strip()}
        if not matched:
            continue
        row["title"] = best["title"]
        row["resonatorImages"] = best.get("resonatorImages", row.get("resonatorImages", {}))
        row["sourceLabel"] = f"{best.get('sourceLabel', 'Community banner schedule')} + Prydwen Banner Overview"
        row["sourceUrls"] = list(dict.fromkeys(best.get("sourceUrls", []) + row.get("sourceUrls", [])))
        row["confidence"] = min(best.get("confidence", 0.9), row.get("confidence", 0.9))
        matching_schedules = [candidate for candidate in schedules
                              if candidate.get("scheduleStatus") == row.get("scheduleStatus")
                              and candidate.get("version") == best.get("version")
                              and candidate.get("phase") == best.get("phase")]
        # Prefer matching character evidence. If roster names differ between
        # sources, use timing only when version, phase and lifecycle identify a
        # single Game8 schedule; never guess between multiple candidates.
        timed = next((candidate for candidate in matching_schedules
                      if names & {n.strip().casefold() for n in re.split(r",|\band\b", candidate.get("title", "")) if n.strip()}), None)
        if timed is None and len(matching_schedules) == 1:
            timed = matching_schedules[0]
        if timed:
            row.update({key: timed[key] for key in ("version", "phase", "startAt", "endAt", "serverTimes", "timingBasis") if key in timed})
            row["sourceLabel"] = "Game8 Banner Schedule + Prydwen Banner Overview"
            row["sourceUrls"] = list(dict.fromkeys(timed.get("sourceUrls", []) + row.get("sourceUrls", [])))
            row["confidence"] = min(timed.get("confidence", 0.9), row.get("confidence", 0.9))
        else:
            row["version"] = best.get("version")
    return snapshots


def prydwen_banner_snapshot(soup, text, url, now):
    """Read only character-linked entries inside Prydwen's banner sections."""
    headings = soup.find_all(["h2", "h3", "h4"])
    output = []
    section_status = None
    for heading in headings:
        label = clean_text(heading.get_text(" ", strip=True))
        low = label.lower()
        if re.search(r"\bcurrent character banners?\b", low):
            section_status = "CURRENT"
            continue
        if re.fullmatch(r"(?:next|upcoming)(?: character)? banners?", low):
            section_status = "UPCOMING"
            continue
        if "expected banner" in low:
            section_status = "UPCOMING"
        if "teased" in low and "banner" in low:
            section_status = "TEASED"
        elif heading.name == "h2":
            # Do not let a banner status bleed into FAQ, guides, or footer links.
            section_status = None
            continue
        version_matches = list(VERSION_RE.finditer(label))
        patch_matches = list(re.finditer(r"\bpatch\s+(\d+\.\d+)\b", label, re.I))
        version_values = [match.group(1) for match in version_matches] + [match.group(1) for match in patch_matches]
        version_values = list(dict.fromkeys(version_values))
        version_value = version_values[0] if len(version_values) == 1 else None
        phase_match = re.search(r"phase\s*(?:([12])|([iv]+))", low)
        phase = int(phase_match.group(1)) if phase_match and phase_match.group(1) else (2 if phase_match and phase_match.group(2) == "ii" else 1 if phase_match and phase_match.group(2) == "i" else None)
        if not section_status or (not version_value and section_status != "TEASED" and not (section_status == "UPCOMING" and "expected banner" in low)):
            continue
        # Restrict a record to the content after its heading and before the
        # next peer/parent heading. Never crawl the remainder of the page.
        names = []
        for anchor in heading.find_all_next("a", href=True):
            next_heading = anchor.find_previous(["h2", "h3", "h4"])
            if next_heading is not heading:
                break
            parts = urlsplit(urljoin(url, anchor["href"]))
            if parts.hostname not in {"prydwen.gg", "www.prydwen.gg"} or not re.search(r"/wuthering-waves/characters?/[^/]+", parts.path, re.I):
                continue
            if anchor.find_parent("div", attrs={"aria-label": re.compile(r"secondary", re.I)}):
                continue
            image = anchor.find("img")
            candidate = clean_text((image.get("alt", "") if image else "") or anchor.get("aria-label", "") or anchor.get("title", "") or anchor.get_text(" ", strip=True))
            # Prydwen renders an "Open <character>" accessibility label on
            # some card links as well as a separate character-name link.
            candidate = re.sub(r"^(?:open|view|profile)\s+", "", candidate, flags=re.I).strip()
            candidate = re.sub(r"\s+(?:open|view|profile)$", "", candidate, flags=re.I).strip()
            if not candidate:
                candidate = parts.path.rstrip("/").split("/")[-1].replace("-", " ").title()
            if (2 <= len(candidate) <= 40 and not re.search(
                    r"banner|weapon|resonator|image|icon|phase|patch|current|upcoming|teased|guide|tier list|privacy|introduction|reroll|beginner|tips|free characters|gacha",
                    candidate, re.I)) and candidate.lower() not in {name.lower() for name in names}:
                names.append(candidate)
        if not names:
            continue
        output.append({
            "title": ", ".join(dict.fromkeys(names)), "version": version_value, "phase": phase,
            "startAt": None, "endAt": None, "status": "COMMUNITY", "scheduleStatus": section_status,
            "confidence": 0.9, "sourceLabel": "Prydwen Banner Overview", "sourceUrls": [url], "snapshotAt": now,
        })
    return output or None


def main():
    items, errors, snapshots, timeline_weapons, next_version_searches = [], [], [], {}, []
    for source in SOURCES:
        try:
            if source['kind'] == 'timeline_weapons':
                timeline_weapons.update(timeline_weapon_details(source))
                continue
            if source['kind'] == 'google_news' and source.get('category') == 'leak-community':
                next_version_searches.append(source)
                continue
            if source['kind'] == 'banner_snapshot':
                snapshot = banner_snapshot(source)
                if snapshot:
                    for row in (snapshot if isinstance(snapshot, list) else [snapshot]):
                        row['snapshotType'] = source.get('snapshotType')
                        snapshots.append(row)
                elif source.get('snapshotType') in {'game8', 'prydwen'}:
                    message = (
                        'Game8 page was fetched, but no complete per-server banner schedule was parsed'
                        if source.get('snapshotType') == 'game8'
                        else 'Prydwen page was fetched, but no character-linked banner entries were parsed'
                    )
                    errors.append(_source_health_entry(source['id'], message, stage='parse'))
                continue
            fn = {'google_news': google_news, 'rss': rss, 'html': html_page, 'official_json': official_json}[source['kind']]
            for item in fn(source):
                if item: items.append(item)
        except Exception as exc:
            errors.append(_source_health_entry(source['id'], exc))

    # Only a dated snapshot explicitly marked current determines the live version.
    # Official news may announce a future patch before that patch goes live.
    now_utc = datetime.now(timezone.utc)
    live_version_candidates = []
    for snapshot in snapshots:
        if not snapshot.get("version"):
            continue
        if snapshot.get("snapshotType") == "game8" and snapshot.get("serverTimes"):
            for timing in snapshot["serverTimes"].values():
                try:
                    start = datetime.fromisoformat(timing["startAt"].replace("Z", "+00:00"))
                    end = datetime.fromisoformat(timing["endAt"].replace("Z", "+00:00"))
                except (AttributeError, KeyError, TypeError, ValueError):
                    continue
                if start <= now_utc <= end:
                    live_version_candidates.append(snapshot["version"])
                    break
            continue
        if snapshot.get("snapshotType") != "current":
            continue
        try:
            start = datetime.fromisoformat(snapshot["startAt"].replace("Z", "+00:00"))
            end = datetime.fromisoformat(snapshot["endAt"].replace("Z", "+00:00"))
        except (AttributeError, TypeError, ValueError):
            continue
        if start <= now_utc <= end:
            live_version_candidates.append(snapshot["version"])
    live_version = max(live_version_candidates, key=lambda value: tuple(map(int, value.split(".")))) if live_version_candidates else None
    snapshots = _remove_stale_prydwen_rows(snapshots, live_version)
    snapshots = _harmonize_prydwen_rows(snapshots, live_version)
    current_version = live_version
    if current_version:
        major, minor = map(int, current_version.split("."))
        next_version = f"{major}.{minor + 1}"
        for source in next_version_searches:
            targeted = dict(source)
            targeted["query"] = f"({source['query']}) (\"Wuthering Waves {next_version}\" OR \"WuWa {next_version}\" OR \"v{next_version}\")"
            try:
                for item in google_news(targeted):
                    if item:
                        target_version = target_version_from_article(item, next_version)
                        if target_version:
                            item["targetVersion"] = target_version
                        items.append(item)
            except Exception as exc:
                errors.append(_source_health_entry(source["id"], exc))
    # A small verified mapping prevents the signature weapons for the known live
    # version from disappearing if the timeline temporarily omits its labels.
    if current_version == "3.7":
        timeline_weapons.update({"Hsin": "Blooming Jadehaven", "Chisa": "Kumokiri", "Iuno": "Moongazer's Sigil",
                                 "Suoming": "Unspoken Rue", "Lynae": "Spectrum Blaster", "Lucilla": "Freeze Frame"})

    for snapshot in snapshots:
        characters = [part.strip() for part in re.split(r",|\band\b", snapshot.get("title", "")) if part.strip()]
        weapons = list(dict.fromkeys(timeline_weapons[name] for name in characters if name in timeline_weapons))
        art = snapshot.pop("bannerArtwork", [])
        if "GenGamer" in snapshot.get("sourceLabel", "") and len(art) >= 6:
            resonator_images = snapshot.setdefault("resonatorImages", {})
            weapon_images = snapshot.setdefault("weaponImages", {})
            for index, character in enumerate(characters[:3]):
                resonator_images[character] = art[index * 2]
                weapon_name = timeline_weapons.get(character)
                if weapon_name:
                    weapon_images[weapon_name] = art[index * 2 + 1]
        if weapons:
            snapshot["weapon"] = ", ".join(weapons)
            snapshot.setdefault("weaponImages", {}).update({name: image for name in weapons if (image := fandom_image(name))})
            snapshot["sourceUrls"] = list(dict.fromkeys(snapshot.get("sourceUrls", []) + ["https://wuwatracker.com/timeline"]))

    # Deduplicate URLs first, then near-identical titles.
    by_url = {}
    for item in items:
        old = by_url.get(item['url'])
        if old is None or (item['priority'], item['publishedAt']) > (old['priority'], old['publishedAt']):
            by_url[item['url']] = item
    by_title = {}
    for item in sorted(by_url.values(), key=lambda z: (z['priority'], z['publishedAt']), reverse=True):
        key = re.sub(r"[^a-z0-9]", "", item['title'].lower())[:140]
        by_title.setdefault(key, item)
    final = sorted(by_title.values(), key=lambda z: z['publishedAt'], reverse=True)[:300]

    versions, banners, resonators, events = extract_entities(final)
    versions, banners, resonators, events = [merge_by_entity(x) for x in (versions, banners, resonators, events)]
    versions.sort(key=lambda x: tuple(map(int, x['version'].split('.'))), reverse=True)

    payload = {
        "schemaVersion": 3,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "refreshSeconds": 1800,
        "latestVersion": live_version,
        "news": final,
        "versions": versions[:30],
        "banners": banners[:80],
        "scheduleSnapshots": snapshots,
        "weaponDetails": timeline_weapons,
        # Keep the complete evidence-bearing collection and also expose convenient
        # lifecycle buckets for clients that want a direct active/upcoming view.
        "activeBanners": [b for b in banners if b.get("startAt") and b.get("endAt") and b["startAt"] <= datetime.now(timezone.utc).isoformat() <= b["endAt"]][:40],
        "upcomingBanners": [b for b in banners if b.get("startAt") and b["startAt"] > datetime.now(timezone.utc).isoformat()][:40],
        "resonators": resonators[:100],
        "upcomingResonators": [r for r in resonators if r.get("version") or r.get("phase")][:60],
        "events": events[:120],
        "sourceHealth": errors,
        "policy": {
            "officialIsAuthoritative": True,
            "leaksAreUnconfirmed": True,
            "multiSourceRaisesConfidenceButDoesNotConfirmLeaks": True,
            "inGameTimerIsFinalAuthority": True,
            "xApiRequired": False,
            "freeXStrategy": "public X pages indexed by Google News RSS; no paid X API token"
        }
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f"wrote {len(final)} news; {len(versions)} versions; {len(banners)} banners; {len(resonators)} resonators; {len(events)} events; {len(errors)} source errors")
    for error in errors:
        print(f"source error [{error.get('sourceId', 'unknown')}/{error.get('stage', 'process')}]: {error.get('error', 'unknown error')}")


if __name__ == '__main__':
    main()
