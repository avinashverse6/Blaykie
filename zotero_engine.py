import re
import unicodedata

import requests

API_BASE = "https://api.zotero.org"
SKIP_TYPES = ("attachment", "note", "annotation")

MAX_RESULTS = 200   # most references loaded per search (change this number)
PAGE_SIZE = 100     # Zotero allows at most 100 items per request


# =====================================================================
#  Zotero search
# =====================================================================

def fetch_page(url, headers, params):
    try:
        response = requests.get(
            url,
            headers=headers,
            params=params,
            timeout=15,
        )
    except requests.RequestException:
        raise RuntimeError("Network error. Check your internet connection.")

    if response.status_code in (401, 403):
        raise RuntimeError("Invalid API key or no access to this library.")
    if response.status_code == 404:
        raise RuntimeError("User ID not found. Check your Zotero User ID.")
    if response.status_code != 200:
        raise RuntimeError(f"Zotero error: {response.status_code}")

    try:
        page = response.json()
    except ValueError:
        raise RuntimeError("Zotero returned invalid data.")

    return page, response.headers.get("Total-Results", "")


def search_zotero(query, user_id, api_key):
    # "items/top" skips child PDFs and notes, so slots are not wasted on them
    url = f"{API_BASE}/users/{user_id}/items/top"
    headers = {
        "Zotero-API-Key": api_key,
        "Zotero-API-Version": "3",
    }

    collected = []
    start = 0

    while len(collected) < MAX_RESULTS:
        params = {
            "q": query,
            "limit": PAGE_SIZE,
            "start": start,
            "format": "json",
        }
        page, total = fetch_page(url, headers, params)
        collected.extend(page)
        start += PAGE_SIZE

        if len(page) < PAGE_SIZE:
            break
        if total.isdigit() and start >= int(total):
            break

    # Remove standalone PDFs, notes and annotations; keep real references
    items = [
        item for item in collected
        if item.get("data", {}).get("itemType") not in SKIP_TYPES
    ]
    return items[:MAX_RESULTS]


# =====================================================================
#  Small helpers
# =====================================================================

def clean(value):
    if value is None:
        return ""
    return str(value).strip()


def end_punct(text):
    """Add a full stop unless the text already ends with . ? or !"""
    text = clean(text)
    if not text:
        return ""
    if text[-1] in ".?!":
        return text
    return text + "."


def sort_text(text):
    """Lowercase, accent-free, letters and digits only (letter-by-letter sort)."""
    text = unicodedata.normalize("NFKD", clean(text).lower())
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    simple = re.sub(r"[^a-z0-9]", "", text)
    return simple or text


def title_sort(data):
    title = clean(data.get("title")).lower()
    title = re.sub(r"^(a|an|the)\s+", "", title)
    return sort_text(title)


def ordinal(number):
    if 10 <= number % 100 <= 20:
        suffix = "th"
    else:
        suffix = {1: "st", 2: "nd", 3: "rd"}.get(number % 10, "th")
    return f"{number}{suffix}"


MINOR_WORDS = {
    "a", "an", "the", "and", "but", "or", "for", "nor", "as", "at", "by",
    "in", "of", "off", "on", "per", "to", "up", "via",
}


def title_case(title, capitalize_words=True):
    """APA title case (no-author in-text citations, journal names)."""
    words = clean(title).split(" ")
    result = []
    new_sentence = True
    for word in words:
        core = re.sub(r"^\W+|\W+$", "", word)
        if not core:
            result.append(word)
            continue

        pieces = word.split("-")
        fixed = []
        for index, piece in enumerate(pieces):
            piece_core = re.sub(r"^\W+|\W+$", "", piece)
            if not piece_core:
                fixed.append(piece)
                continue
            is_minor = piece_core.lower() in MINOR_WORDS
            first_piece = index == 0
            if is_minor and not (new_sentence and first_piece):
                fixed.append(piece.lower())
            elif capitalize_words and piece_core.islower():
                position = piece.find(piece_core[0])
                fixed.append(piece[:position] + piece[position:].capitalize())
            else:
                fixed.append(piece)
        result.append("-".join(fixed))
        new_sentence = word.endswith((":", "?", ".", "!", "—"))
    return " ".join(result)


# =====================================================================
#  Dates
# =====================================================================

MONTH_NAMES = [
    "January", "February", "March", "April", "May", "June", "July",
    "August", "September", "October", "November", "December",
]
MONTH_LOOKUP = {}
for _index, _name in enumerate(MONTH_NAMES, 1):
    MONTH_LOOKUP[_name.lower()] = _index
    MONTH_LOOKUP[_name[:3].lower()] = _index
MONTH_LOOKUP["sept"] = 9


def parse_date(text):
    """Returns (year, month, day, label). label is 'n.d.' or 'in press'
    when there is no usable year."""
    text = clean(text)
    low = text.lower()

    if not text or low in ("n.d.", "nd", "no date", "undated"):
        return None, None, None, "n.d."
    if "in press" in low or "forthcoming" in low:
        return None, None, None, "in press"

    iso = re.search(r"\b(\d{4})[-/.](\d{1,2})(?:[-/.](\d{1,2}))?\b", text)
    if iso:
        month = int(iso.group(2))
        day = int(iso.group(3)) if iso.group(3) else None
        if 1 <= month <= 12:
            if day is not None and not 1 <= day <= 31:
                day = None
            return int(iso.group(1)), month, day, ""

    year_match = re.search(r"\b(1\d{3}|20\d{2})\b", text)
    if not year_match:
        return None, None, None, "n.d."

    year = int(year_match.group(1))
    rest = text[:year_match.start()] + " " + text[year_match.end():]

    month = None
    for word in re.findall(r"[A-Za-z]+", rest):
        found = MONTH_LOOKUP.get(word.lower())
        if found:
            month = found
            break

    day = None
    if month:
        day_match = re.search(r"\b(\d{1,2})(?:st|nd|rd|th)?\b", rest)
        if day_match and 1 <= int(day_match.group(1)) <= 31:
            day = int(day_match.group(1))

    return year, month, day, ""


def get_year(data):
    year, month, day, label = parse_date(data.get("date"))
    if year is None:
        return label
    return str(year)


def date_text(data, level="year", suffix=""):
    """level: 'year' -> 2020, 'month' -> 2020, May, 'day' -> 2020, May 12"""
    year, month, day, label = parse_date(data.get("date"))
    if year is None:
        return label + ("-" + suffix if suffix else "")

    text = str(year) + suffix
    if level in ("month", "day") and month:
        text += ", " + MONTH_NAMES[month - 1]
        if level == "day" and day:
            text += f" {day}"
    return text


def year_sort_value(data):
    year, month, day, label = parse_date(data.get("date"))
    if year is not None:
        return year
    if label == "in press":
        return 99999
    return 0


# =====================================================================
#  People
# =====================================================================

def get_creators(data, kind):
    people = []
    for creator in data.get("creators", []) or []:
        creator_type = creator.get("creatorType")
        if kind == "author":
            if creator_type not in (None, "author"):
                continue
        elif creator_type != kind:
            continue

        last = clean(creator.get("lastName"))
        first = clean(creator.get("firstName"))
        name = clean(creator.get("name"))

        if last:
            people.append({"first": first, "last": last, "group": False})
        elif name:
            people.append({"first": "", "last": name, "group": True})
    return people


def get_authors(data):
    return get_creators(data, "author")


def get_editors(data):
    return get_creators(data, "editor")


def lead_people(data):
    """Authors; for a whole edited book with no authors, the editors."""
    authors = get_authors(data)
    if authors:
        return authors, "author"
    if data.get("itemType") == "book":
        editors = get_editors(data)
        if editors:
            return editors, "editor"
    return [], ""


def initials(first_name):
    parts = []
    for word in clean(first_name).replace(".", " ").split():
        pieces = [piece for piece in word.split("-") if piece]
        if not pieces:
            continue
        parts.append("-".join(piece[0].upper() + "." for piece in pieces))
    return " ".join(parts)


def author_name(person):
    """Reference-list style: Last, I. I."""
    if person["group"]:
        return person["last"]
    short = initials(person["first"])
    if short:
        return f"{person['last']}, {short}"
    return person["last"]


def apa_author_name(author):
    return author_name(author)


def format_author_list(names):
    count = len(names)
    if count == 0:
        return ""
    if count == 1:
        return names[0]
    if count == 2:
        return f"{names[0]}, & {names[1]}"
    if count <= 20:
        return ", ".join(names[:-1]) + ", & " + names[-1]
    return ", ".join(names[:19]) + ", . . . " + names[-1]


def editor_display(person):
    """Initials first, for 'In A. Editor (Ed.),'"""
    if person["group"]:
        return person["last"]
    short = initials(person["first"])
    return f"{short} {person['last']}".strip()


def format_editor_list(people):
    names = [editor_display(person) for person in people]
    if not names:
        return ""
    if len(names) == 1:
        return f"{names[0]} (Ed.)"
    if len(names) == 2:
        return f"{names[0]} & {names[1]} (Eds.)"
    return ", ".join(names[:-1]) + f", & {names[-1]} (Eds.)"


# =====================================================================
#  In-text citations
# =====================================================================

QUOTED_TITLE_TYPES = {
    "journalArticle", "bookSection", "newspaperArticle", "magazineArticle",
    "blogPost", "conferencePaper", "encyclopediaArticle", "dictionaryEntry",
    "preprint", "forumPost",
}


def title_label(data):
    title = title_case(clean(data.get("title")) or "Untitled")
    words = title.split()
    if len(words) > 6:
        title = " ".join(words[:6])
    title = title.rstrip(".,:;")
    if data.get("itemType") in QUOTED_TITLE_TYPES:
        return f'"{title}"'
    return title


def surname_label(person, with_initials):
    if with_initials and person["first"] and not person["group"]:
        return f"{initials(person['first'])} {person['last']}"
    return person["last"]


def intext_label(data, joiner="&", first_initials=False):
    people, role = lead_people(data)
    if not people:
        return title_label(data)

    first = surname_label(people[0], first_initials)
    if len(people) == 1:
        return first
    if len(people) == 2:
        return f"{first} {joiner} {people[1]['last']}"
    return f"{first} et al."


def join_label_year(label, year_text):
    if label.endswith('"'):
        return f'{label[:-1]},"' + " " + year_text
    return f"{label}, {year_text}"


def apa_in_text(data, suffix="", first_initials=False):
    label = intext_label(data, "&", first_initials)
    return "(" + join_label_year(label, date_text(data, "year", suffix)) + ")"


def apa_narrative(data, suffix="", first_initials=False):
    label = intext_label(data, "and", first_initials)
    return f"{label} ({date_text(data, 'year', suffix)})"


# =====================================================================
#  Selections: duplicates, a/b suffixes, sorting
# =====================================================================

def item_id(item):
    return item.get("key") or id(item)


def unique_items(items):
    seen = set()
    result = []
    for item in items:
        identity = item_id(item)
        if identity in seen:
            continue
        seen.add(identity)
        result.append(item)
    return result


def author_key(data):
    people, role = lead_people(data)
    if people:
        return tuple((sort_text(p["last"]), sort_text(p["first"])) for p in people)
    return (("title", title_sort(data)),)


def reference_sort_key(item):
    data = item.get("data", {})
    people, role = lead_people(data)
    if people:
        names = [(sort_text(p["last"]), sort_text(p["first"])) for p in people]
    else:
        names = [(title_sort(data), "")]
    return (names, year_sort_value(data), title_sort(data))


sort_key = reference_sort_key


def sorted_items(items):
    return sorted(items, key=reference_sort_key)


def year_suffixes(items):
    """Same author(s) and same year -> a, b, c ... (alphabetical by title)."""
    groups = {}
    for item in items:
        data = item.get("data", {})
        key = (author_key(data), parse_date(data.get("date"))[0], get_year(data))
        groups.setdefault(key, []).append(item)

    letters = "abcdefghijklmnopqrstuvwxyz"
    suffixes = {}
    for group in groups.values():
        if len(group) < 2:
            continue
        group.sort(key=lambda it: title_sort(it.get("data", {})))
        for index, item in enumerate(group):
            suffixes[item_id(item)] = letters[index % 26]
    return suffixes


def surnames_needing_initials(items):
    """First authors who share a surname but have different initials."""
    seen = {}
    for item in items:
        people, role = lead_people(item.get("data", {}))
        if not people or people[0]["group"]:
            continue
        surname = sort_text(people[0]["last"])
        seen.setdefault(surname, set()).add(initials(people[0]["first"]))
    return {surname for surname, variants in seen.items() if len(variants) > 1}


def needs_initials(data, flagged):
    people, role = lead_people(data)
    return bool(people) and sort_text(people[0]["last"]) in flagged


def multiple_in_text(items):
    """One combined citation: (Ahmed, 2019; Kumar, 2020; Singh, 1998, 2021)"""
    items = unique_items(items)
    if not items:
        return ""

    suffixes = year_suffixes(items)
    flagged = surnames_needing_initials(items)

    order = []
    groups = {}
    for item in sorted_items(items):
        data = item.get("data", {})
        label = intext_label(data, "&", needs_initials(data, flagged))
        year_text = date_text(data, "year", suffixes.get(item_id(item), ""))
        if label not in groups:
            groups[label] = []
            order.append(label)
        groups[label].append(year_text)

    parts = [join_label_year(label, ", ".join(groups[label])) for label in order]
    return "(" + "; ".join(parts) + ")"


def narrative_text(items):
    items = unique_items(items)
    suffixes = year_suffixes(items)
    flagged = surnames_needing_initials(items)
    parts = []
    for item in sorted_items(items):
        data = item.get("data", {})
        parts.append(apa_narrative(
            data,
            suffixes.get(item_id(item), ""),
            needs_initials(data, flagged),
        ))
    return "; ".join(parts)


def multiple_citation(items, numbers):
    selected = []
    seen = set()
    for number in numbers:
        if number in seen:
            continue
        if 1 <= number <= len(items):
            selected.append(items[number - 1])
            seen.add(number)
    return multiple_in_text(selected)


# =====================================================================
#  Reference list entries
# =====================================================================

BAD_URL_HOSTS = (
    "ebscohost", "proquest", "ezproxy", "galegroup", "gale.com",
    "infotrac", "lexisnexis", "libproxy", "oclc.org", "worldcat",
)


def usable_url(url):
    url = clean(url)
    if not url:
        return ""
    lowered = url.lower()
    if any(host in lowered for host in BAD_URL_HOSTS):
        return ""
    return url


def get_doi(data):
    doi = clean(data.get("DOI"))
    if not doi:
        match = re.search(r"^\s*DOI:\s*(\S+)", clean(data.get("extra")),
                          flags=re.I | re.M)
        if match:
            doi = match.group(1)
    if not doi:
        return ""
    doi = re.sub(r"^(?:doi:\s*|https?://(?:dx\.)?doi\.org/)", "", doi,
                 flags=re.I)
    return "https://doi.org/" + doi.strip()


def format_pages(pages):
    pages = clean(pages)
    if not pages:
        return ""
    return re.sub(r"\s*[-\u2010\u2011\u2012\u2013\u2014]+\s*", "–", pages)


def page_label(pages, prefix=True):
    """'12–20' -> 'pp. 12–20', '12' -> 'p. 12'"""
    pages = format_pages(pages)
    if not pages:
        return ""
    if not prefix:
        return pages
    if "–" in pages or "," in pages:
        return "pp. " + pages
    return "p. " + pages


def article_number(pages):
    pages = clean(pages)
    if re.fullmatch(r"[A-Za-z]{1,3}\d{3,}[A-Za-z0-9.\-]*", pages) or \
            re.fullmatch(r"\d{5,}", pages):
        return "Article " + pages
    return ""


def format_edition(edition):
    text = clean(edition)
    if not text:
        return ""

    words = {
        "first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5,
        "sixth": 6, "seventh": 7, "eighth": 8, "ninth": 9, "tenth": 10,
    }
    low = text.lower().rstrip(".")
    low = re.sub(r"\s*(edition|ed)$", "", low).strip()

    number = None
    if low.isdigit():
        number = int(low)
    else:
        match = re.fullmatch(r"(\d+)(?:st|nd|rd|th)", low)
        if match:
            number = int(match.group(1))
        elif low in words:
            number = words[low]

    if number is not None:
        if number == 1:
            return ""
        return f"{ordinal(number)} ed."

    text = re.sub(r"\s*edition$", "", text, flags=re.I).strip()
    if text.lower().rstrip(".") in ("rev", "revised"):
        return "Rev. ed."
    if not re.search(r"\bed\.?$", text, flags=re.I):
        text += " ed."
    elif not text.endswith("."):
        text += "."
    return text


def same_name(first, second):
    first, second = sort_text(first), sort_text(second)
    return bool(first) and first == second


def thesis_label(thesis_type):
    text = clean(thesis_type)
    if re.search(r"\b(ph\.?\s?d|d\.?\s?phil|doctor\w*|dissertation)\b", text,
                 flags=re.I):
        return "Doctoral dissertation"
    if re.search(r"\b(master\w*|m\.?\s?phil|m\.?\s?sc|m\.?\s?a|m\.?\s?ed|mba|m\.?\s?tech)\b",
                 text, flags=re.I):
        return "Master's thesis"
    return text[:1].upper() + text[1:] if text else "Thesis"


def periodical_name(name):
    """Journal / magazine / newspaper names: lowercase 'Of', 'And' etc."""
    name = clean(name)
    if not name or name.isupper():
        return name
    return title_case(name, capitalize_words=False)


class Ref:
    """Builds a reference as a list of (text, italic) pieces."""

    def __init__(self):
        self.parts = []

    def add(self, text, italic=False):
        if text:
            self.parts.append((text, bool(italic)))

    def result(self):
        merged = []
        for text, italic in self.parts:
            if merged and merged[-1][1] == italic:
                merged[-1] = (merged[-1][0] + text, italic)
            else:
                merged.append((text, italic))
        merged = [(re.sub(r" {2,}", " ", text), italic) for text, italic in merged]
        if merged:
            merged[0] = (merged[0][0].lstrip(), merged[0][1])
            merged[-1] = (merged[-1][0].rstrip(), merged[-1][1])
        return [piece for piece in merged if piece[0]]


def add_title(ref, title, italic, extras=""):
    title = clean(title) or "Untitled"
    if extras:
        ref.add(title, italic)
        ref.add(" " + extras + ".")
    elif title[-1] in ".?!":
        ref.add(title, italic)
    else:
        ref.add(title, italic)
        ref.add(".")


def begin(ref, data, suffix, level, title, italic, extras=""):
    """Author. (Date). Title.   or   Title. (Date).  when there is no author.
    Returns the author text (used to drop a publisher that repeats it)."""
    people, role = lead_people(data)
    date = f"({date_text(data, level, suffix)})"

    if people:
        text = format_author_list([author_name(person) for person in people])
        if role == "editor":
            text += " (Ed.)" if len(people) == 1 else " (Eds.)"
        ref.add(end_punct(text) + " " + date + ". ")
        add_title(ref, title, italic, extras)
        ref.add(" ")
        return text

    add_title(ref, title, italic, extras)
    ref.add(" " + date + ". ")
    return ""


def add_link(ref, data, allow_url=True):
    doi = get_doi(data)
    if doi:
        ref.add(" " + doi)
        return
    if allow_url:
        url = usable_url(data.get("url"))
        if url:
            ref.add(" " + url)


def add_publisher(ref, publisher, author_text):
    publisher = clean(publisher)
    if publisher and not same_name(publisher, author_text):
        ref.add(publisher + ".")


def add_periodical(ref, data, pages_prefix=False):
    """Journal, 12(3), 45–67."""
    journal = periodical_name(data.get("publicationTitle"))
    volume = clean(data.get("volume"))
    issue = clean(data.get("issue"))
    pages = clean(data.get("pages"))

    if not journal:
        return

    ref.add(journal, True)
    if volume:
        ref.add(", ")
        ref.add(volume, True)
        if issue:
            ref.add(f"({issue})")
    elif issue:
        ref.add(f", ({issue})")

    locator = article_number(pages) or page_label(pages, pages_prefix)
    if locator:
        ref.add(", " + locator)
    ref.add(".")


def build_journal(ref, data, suffix):
    begin(ref, data, suffix, "year", data.get("title"), False)
    add_periodical(ref, data)
    add_link(ref, data)


def build_magazine(ref, data, suffix):
    begin(ref, data, suffix, "day", data.get("title"), False)
    add_periodical(ref, data)
    add_link(ref, data)


def build_newspaper(ref, data, suffix):
    begin(ref, data, suffix, "day", data.get("title"), False)
    add_periodical(ref, data, pages_prefix=True)
    add_link(ref, data)


def build_book(ref, data, suffix):
    details = []
    edition = format_edition(data.get("edition"))
    if edition:
        details.append(edition)
    volume = clean(data.get("volume"))
    if volume:
        details.append(f"Vol. {volume}")
    extras = f"({', '.join(details)})" if details else ""

    author_text = begin(ref, data, suffix, "year", data.get("title"), True, extras)
    add_publisher(ref, data.get("publisher"), author_text)

    if get_doi(data):
        add_link(ref, data, allow_url=False)
    elif not clean(data.get("publisher")):
        add_link(ref, data)


def editors_in_chapter(ref, data, container_title):
    """In A. Editor (Ed.), Book title (2nd ed., pp. 1–10)."""
    editors = format_editor_list(get_editors(data))
    if not container_title and not editors:
        return

    ref.add("In ")
    if editors:
        ref.add(editors + (", " if container_title else ""))
    if container_title:
        ref.add(container_title, True)

    details = []
    edition = format_edition(data.get("edition"))
    if edition:
        details.append(edition)
    volume = clean(data.get("volume"))
    if volume:
        details.append(f"Vol. {volume}")
    locator = page_label(data.get("pages"))
    if locator:
        details.append(locator)
    if details:
        ref.add(f" ({', '.join(details)})")
    ref.add(". ")


def build_chapter(ref, data, suffix, container_field="bookTitle"):
    author_text = begin(ref, data, suffix, "year", data.get("title"), False)
    editors_in_chapter(ref, data, clean(data.get(container_field)))
    add_publisher(ref, data.get("publisher"), author_text)
    add_link(ref, data)


def build_report(ref, data, suffix):
    extras = []
    number = clean(data.get("reportNumber"))
    report_type = clean(data.get("reportType"))
    if number:
        if re.match(r"(?i)^(report|rep\.|no\.?|publication|technical|working)", number):
            extras.append(f"({number})")
        else:
            label = title_case(report_type) if report_type else "Report"
            extras.append(f"({label} No. {number})")
    elif report_type:
        extras.append(f"[{report_type}]")

    author_text = begin(ref, data, suffix, "year", data.get("title"), True,
                        " ".join(extras))
    add_publisher(ref, data.get("institution") or data.get("publisher"), author_text)
    add_link(ref, data)


def build_thesis(ref, data, suffix):
    label = thesis_label(data.get("thesisType"))
    university = clean(data.get("university") or data.get("institution"))
    archive = clean(data.get("archive"))
    url = usable_url(data.get("url"))
    published = bool(url or archive)

    if published:
        inner = f"{label}, {university}" if university else label
        extras = f"[{inner}]"
    else:
        extras = f"[Unpublished {label[:1].lower() + label[1:]}]"

    author_text = begin(ref, data, suffix, "year", data.get("title"), True, extras)

    if published:
        if archive:
            ref.add(archive + ".")
        add_link(ref, data)
    else:
        add_publisher(ref, university, author_text)


def build_webpage(ref, data, suffix):
    extras = f"[{clean(data.get('websiteType'))}]" if clean(data.get("websiteType")) else ""
    author_text = begin(ref, data, suffix, "day", data.get("title"), True, extras)
    site = clean(data.get("websiteTitle"))
    if site and not same_name(site, author_text):
        ref.add(site + ".")
    url = usable_url(data.get("url"))
    if url:
        ref.add(" " + url)


def build_blog(ref, data, suffix):
    begin(ref, data, suffix, "day", data.get("title"), False)
    blog = clean(data.get("blogTitle") or data.get("websiteTitle"))
    if blog:
        ref.add(blog, True)
        ref.add(".")
    url = usable_url(data.get("url"))
    if url:
        ref.add(" " + url)


def build_conference(ref, data, suffix):
    proceedings = clean(data.get("proceedingsTitle"))
    if proceedings:
        build_chapter(ref, data, suffix, "proceedingsTitle")
        return

    conference = clean(data.get("conferenceName"))
    if conference:
        place = clean(data.get("place"))
        author_text = begin(ref, data, suffix, "day", data.get("title"), True,
                            "[Paper presentation]")
        ref.add(conference + (f", {place}" if place else "") + ".")
        add_link(ref, data)
        return

    begin(ref, data, suffix, "year", data.get("title"), False)
    add_publisher(ref, data.get("publisher"), "")
    add_link(ref, data)


def build_preprint(ref, data, suffix):
    begin(ref, data, suffix, "year", data.get("title"), False)
    repository = clean(data.get("repository"))
    if repository:
        ref.add(repository, True)
        ref.add(".")
    add_link(ref, data)


def build_document(ref, data, suffix):
    build_report(ref, data, suffix)


def build_generic(ref, data, suffix):
    if clean(data.get("publicationTitle")):
        build_journal(ref, data, suffix)
    elif clean(data.get("publisher")):
        build_book(ref, data, suffix)
    else:
        begin(ref, data, suffix, "year", data.get("title"), False)
        add_link(ref, data)


BUILDERS = {
    "journalArticle": build_journal,
    "magazineArticle": build_magazine,
    "newspaperArticle": build_newspaper,
    "book": build_book,
    "bookSection": build_chapter,
    "report": build_report,
    "document": build_document,
    "thesis": build_thesis,
    "webpage": build_webpage,
    "blogPost": build_blog,
    "conferencePaper": build_conference,
    "preprint": build_preprint,
}


def apa_reference_parts(data, suffix=""):
    """The reference as [(text, is_italic), ...] (used for Word export)."""
    ref = Ref()
    builder = BUILDERS.get(data.get("itemType", ""), build_generic)
    builder(ref, data, suffix)
    return ref.result()


def apa_full_reference(data, suffix=""):
    return "".join(text for text, italic in apa_reference_parts(data, suffix))


def references_text(items):
    """All selected references: alphabetical, with a/b suffixes where needed."""
    items = unique_items(items)
    suffixes = year_suffixes(items)
    entries = [
        apa_full_reference(item.get("data", {}), suffixes.get(item_id(item), ""))
        for item in sorted_items(items)
    ]
    return "\n\n".join(entries)


# =====================================================================
#  Terminal (Termux / PC) version
# =====================================================================

def display_search_results(items):
    print("\nSearch results:\n")
    for number, item in enumerate(items, 1):
        data = item.get("data", {})
        title = data.get("title", "[No title]")
        if len(title) > 90:
            title = title[:87] + "..."
        authors = get_authors(data)
        if not authors:
            author_text = "Unknown author"
        elif len(authors) == 1:
            author_text = authors[0]["last"]
        elif len(authors) == 2:
            author_text = f'{authors[0]["last"]} & {authors[1]["last"]}'
        else:
            author_text = f'{authors[0]["last"]} et al.'
        print(f"{number}. {author_text} ({get_year(data)}) — {title}")


def single_reference_menu(item):
    data = item.get("data", {})

    print("\n1. Parenthetical citation")
    print("2. Narrative citation")
    print("3. Full reference")
    print("4. Show all")
    choice = input("\nChoose an option: ").strip()

    if choice in ("1", "4"):
        print("\nParenthetical:")
        print(apa_in_text(data))
    if choice in ("2", "4"):
        print("\nNarrative:")
        print(apa_narrative(data))
    if choice in ("3", "4"):
        print("\nFull reference:")
        print(apa_full_reference(data))
    if choice not in ("1", "2", "3", "4"):
        print("Invalid option.")


def main():
    print("================================")
    print(" CITATION MANAGER")
    print("================================")

    user_id = input("\nZotero User ID: ").strip()
    api_key = input("Zotero API Key: ").strip()
    query = input("Search Zotero: ").strip()

    if not user_id or not api_key or not query:
        print("User ID, API key and search term are all required.")
        return

    try:
        items = search_zotero(query, user_id, api_key)
    except RuntimeError as error:
        print(error)
        return

    if not items:
        print("No references found.")
        return

    display_search_results(items)

    print("\n1. Single reference")
    print("2. Multiple references")
    option = input("\nChoose an option: ").strip()

    if option == "1":
        choice = input("\nEnter reference number: ").strip()
        if not choice.isdigit() or not 1 <= int(choice) <= len(items):
            print("Invalid reference number.")
            return
        single_reference_menu(items[int(choice) - 1])

    elif option == "2":
        numbers_text = input("\nEnter numbers separated by commas (1,3,5): ")
        try:
            numbers = [
                int(part.strip())
                for part in numbers_text.split(",")
                if part.strip()
            ]
        except ValueError:
            print("Invalid numbers.")
            return
        citation = multiple_citation(items, numbers)
        print(citation if citation else "No valid references selected.")

    else:
        print("Invalid option.")


if __name__ == "__main__":
    main()
