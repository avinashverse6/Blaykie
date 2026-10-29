import re

import requests

API_BASE = "https://api.zotero.org"
SKIP_TYPES = ("attachment", "note", "annotation")


def search_zotero(query, user_id, api_key):
    url = f"{API_BASE}/users/{user_id}/items"
    headers = {
        "Zotero-API-Key": api_key,
        "Zotero-API-Version": "3",
    }
    params = {
        "q": query,
        "limit": 50,
        "format": "json",
    }

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
        items = response.json()
    except ValueError:
        raise RuntimeError("Zotero returned invalid data.")

    # Remove PDFs, notes and annotations; keep real references only
    return [
        item for item in items
        if item.get("data", {}).get("itemType") not in SKIP_TYPES
    ]


def get_authors(data):
    authors = []
    for creator in data.get("creators", []):
        if creator.get("creatorType") not in (None, "author"):
            continue
        if creator.get("lastName"):
            authors.append({
                "first": (creator.get("firstName") or "").strip(),
                "last": creator.get("lastName", "").strip(),
            })
        elif creator.get("name"):
            authors.append({
                "first": "",
                "last": creator.get("name", "").strip(),
            })
    return authors


def get_year(data):
    date = data.get("date", "") or ""
    match = re.search(r"\b(?:19|20)\d{2}\b", date)
    if match:
        return match.group(0)
    return "n.d."


def initials(first_name):
    parts = first_name.replace(".", ". ").split()
    return " ".join(f"{part[0].upper()}." for part in parts if part)


def apa_author_name(author):
    last = author["last"]
    first = initials(author["first"])
    if first:
        return f"{last}, {first}"
    return last


def apa_in_text(data):
    authors = get_authors(data)
    year = get_year(data)

    if not authors:
        return f"(Unknown author, {year})"
    if len(authors) == 1:
        return f"({authors[0]['last']}, {year})"
    if len(authors) == 2:
        return f"({authors[0]['last']} & {authors[1]['last']}, {year})"
    return f"({authors[0]['last']} et al., {year})"


def apa_narrative(data):
    authors = get_authors(data)
    year = get_year(data)

    if not authors:
        return f"Unknown author ({year})"
    if len(authors) == 1:
        return f"{authors[0]['last']} ({year})"
    if len(authors) == 2:
        return f"{authors[0]['last']} and {authors[1]['last']} ({year})"
    return f"{authors[0]['last']} et al. ({year})"


def sort_key(item):
    data = item.get("data", {})
    authors = get_authors(data)
    surname = authors[0]["last"].lower() if authors else "zzz"
    return (surname, get_year(data))


def sorted_items(items):
    return sorted(items, key=sort_key)


def multiple_in_text(items):
    """One combined citation, alphabetical: (Ahmed, 2020; Khan, 2019)"""
    parts = [
        apa_in_text(item.get("data", {})).strip("()")
        for item in sorted_items(items)
    ]
    if not parts:
        return ""
    return "(" + "; ".join(parts) + ")"


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


def format_pages(pages):
    if not pages:
        return ""
    return pages.replace("-", "–")


def ordinal(number):
    if 10 <= number % 100 <= 20:
        suffix = "th"
    else:
        suffix = {1: "st", 2: "nd", 3: "rd"}.get(number % 10, "th")
    return f"{number}{suffix}"


def end_title(title):
    title = title.strip()
    if not title:
        return ""
    if title[-1] in ".?!":
        return title
    return title + "."


def apa_full_reference(data):
    authors = get_authors(data)
    year = get_year(data)

    formatted_authors = [apa_author_name(author) for author in authors]

    if len(formatted_authors) == 1:
        author_text = formatted_authors[0]
    elif len(formatted_authors) == 2:
        author_text = f"{formatted_authors[0]}, & {formatted_authors[1]}"
    elif formatted_authors:
        author_text = (
            ", ".join(formatted_authors[:-1]) + ", & " + formatted_authors[-1]
        )
    else:
        author_text = ""

    title = (data.get("title") or "").strip()
    item_type = data.get("itemType", "")
    journal = (data.get("publicationTitle") or "").strip()
    volume = str(data.get("volume") or "").strip()
    issue = str(data.get("issue") or "").strip()
    pages = format_pages((data.get("pages") or "").strip())
    doi = (data.get("DOI") or "").strip()
    url = (data.get("url") or "").strip()
    publisher = (data.get("publisher") or "").strip()
    institution = (data.get("institution") or "").strip()
    university = (data.get("university") or "").strip()
    report_type = (data.get("reportType") or "").strip()
    thesis_type = (data.get("thesisType") or "").strip()
    book_title = (data.get("bookTitle") or "").strip()
    edition = (data.get("edition") or "").strip()
    website_title = (data.get("websiteTitle") or "").strip()
    blog_title = (data.get("blogTitle") or "").strip()

    if author_text and not author_text.endswith("."):
        author_text += "."
    author_part = f"{author_text} ({year})." if author_text else f"({year})."
    title_end = end_title(title)

    if item_type == "journalArticle":
        reference = f"{author_part} {title_end}"
        if journal:
            reference += f" {journal}"
            if volume:
                reference += f", {volume}"
            if issue:
                reference += f"({issue})"
            if pages:
                reference += f", {pages}"
            reference += "."

    elif item_type == "book":
        reference = f"{author_part} {title}"
        if edition:
            edition_text = edition
            if edition_text.isdigit():
                number = int(edition_text)
                if number == 1:
                    edition_text = ""
                else:
                    edition_text = ordinal(number)
            if edition_text:
                if not edition_text.lower().endswith("ed."):
                    edition_text += " ed."
                reference += f" ({edition_text})"
        reference += "."
        if publisher:
            reference += f" {publisher}."

    elif item_type == "bookSection":
        editors = []
        for creator in data.get("creators", []):
            if creator.get("creatorType") != "editor":
                continue
            first = (creator.get("firstName") or "").strip()
            last = (creator.get("lastName") or "").strip()
            name = (creator.get("name") or "").strip()
            if last:
                editor_name = f"{initials(first)} {last}".strip()
            elif name:
                editor_name = name
            else:
                continue
            editors.append(editor_name)

        if len(editors) == 1:
            editor_text = f"{editors[0]} (Ed.)"
        elif len(editors) == 2:
            editor_text = f"{editors[0]}, & {editors[1]} (Eds.)"
        elif editors:
            editor_text = ", ".join(editors[:-1]) + f", & {editors[-1]} (Eds.)"
        else:
            editor_text = ""

        reference = f"{author_part} {title_end}"
        if editor_text and book_title:
            reference += f" In {editor_text}, {book_title}"
        elif book_title:
            reference += f" In {book_title}"
        elif editor_text:
            reference += f" In {editor_text}"
        if pages:
            reference += f" (pp. {pages})"
        reference += "."
        if publisher:
            reference += f" {publisher}."

    elif item_type == "report":
        reference = f"{author_part} {title}"
        if report_type:
            reference += f" [{report_type}]"
        reference += "."
        organization = institution or publisher
        if organization:
            reference += f" {organization}."

    elif item_type == "thesis":
        thesis_label = thesis_type or "Thesis"
        organization = university or institution
        reference = f"{author_part} {title}"
        if organization:
            reference += f" [{thesis_label}, {organization}]."
        else:
            reference += f" [{thesis_label}]."

    elif item_type == "webpage":
        reference = f"{author_part} {title_end}"
        if website_title:
            reference += f" {website_title}."
        if url:
            reference += f" {url}"

    elif item_type == "blogPost":
        reference = f"{author_part} {title_end}"
        if blog_title:
            reference += f" {blog_title}."
        if url:
            reference += f" {url}"

    else:
        reference = f"{author_part} {title_end}"
        if journal:
            reference += f" {journal}"
            if volume:
                reference += f", {volume}"
            if issue:
                reference += f"({issue})"
            if pages:
                reference += f", {pages}"
            reference += "."
        elif publisher:
            reference += f" {publisher}."

    if doi:
        reference += f" https://doi.org/{doi}"
    elif url and item_type not in ("webpage", "blogPost"):
        reference += f" {url}"

    return reference.strip()


# ---------------- Terminal (Termux / PC) version ----------------

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
    print(" ZOTERO CITATION MANAGER")
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
