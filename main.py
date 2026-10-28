from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.checkbox import CheckBox
from kivy.uix.scrollview import ScrollView
from kivy.core.clipboard import Clipboard

from zotero_engine import search_zotero, apa_in_text, apa_narrative, apa_full_reference


class ZoteroApp(App):

    def build(self):
        self.results = []
        self.selected = []

        root = BoxLayout(
            orientation="vertical",
            padding=15,
            spacing=10
        )

        title = Label(
            text="Zotero Citation Manager",
            font_size="24sp",
            size_hint_y=None,
            height=45
        )
        root.add_widget(title)

        credentials = GridLayout(
            cols=2,
            spacing=8,
            size_hint_y=None,
            height=100
        )

        credentials.add_widget(Label(text="User ID"))
        self.user_id = TextInput(
            multiline=False,
            hint_text="Zotero User ID"
        )
        credentials.add_widget(self.user_id)

        credentials.add_widget(Label(text="API Key"))
        self.api_key = TextInput(
            multiline=False,
            password=True,
            hint_text="Zotero API Key"
        )
        credentials.add_widget(self.api_key)

        root.add_widget(credentials)

        search_row = BoxLayout(
            size_hint_y=None,
            height=50,
            spacing=8
        )

        self.search_input = TextInput(
            multiline=False,
            hint_text="Search Zotero..."
        )
        search_row.add_widget(self.search_input)

        search_button = Button(text="SEARCH")
        search_button.bind(on_press=self.do_search)
        search_row.add_widget(search_button)

        root.add_widget(search_row)

        self.status = Label(
            text="Enter your Zotero credentials and search.",
            size_hint_y=None,
            height=35
        )
        root.add_widget(self.status)

        scroll = ScrollView()
        self.results_box = GridLayout(
            cols=1,
            spacing=5,
            size_hint_y=None
        )
        self.results_box.bind(
            minimum_height=self.results_box.setter("height")
        )
        scroll.add_widget(self.results_box)
        root.add_widget(scroll)

        action_row = GridLayout(
            cols=2,
            spacing=8,
            size_hint_y=None,
            height=105
        )

        citation_button = Button(text="IN-TEXT CITATION")
        citation_button.bind(on_press=self.copy_citation)
        action_row.add_widget(citation_button)

        reference_button = Button(text="FULL APA 7")
        reference_button.bind(on_press=self.copy_reference)
        action_row.add_widget(reference_button)

        narrative_button = Button(text="NARRATIVE")
        narrative_button.bind(on_press=self.copy_narrative)
        action_row.add_widget(narrative_button)

        clear_button = Button(text="CLEAR")
        clear_button.bind(on_press=self.clear_results)
        action_row.add_widget(clear_button)

        root.add_widget(action_row)

        return root

    def do_search(self, instance):
        user_id = self.user_id.text.strip()
        api_key = self.api_key.text.strip()
        query = self.search_input.text.strip()

        if not user_id or not api_key:
            self.status.text = "Please enter Zotero User ID and API Key."
            return

        if not query:
            self.status.text = "Please enter a search term."
            return

        self.status.text = "Searching Zotero..."
        self.results_box.clear_widgets()
        self.selected = []

        try:
            self.results = search_zotero(query, user_id, api_key)
        except Exception as error:
            self.status.text = f"Error: {error}"
            return

        if not self.results:
            self.status.text = "No references found."
            return

        self.status.text = f"{len(self.results)} result(s) found."

        for number, item in enumerate(self.results):
            data = item.get("data", {})
            title = data.get("title", "[No title]")
            year = self.get_year(data)
            authors = self.get_authors(data)

            if len(authors) == 0:
                author_text = "Unknown author"
            elif len(authors) == 1:
                author_text = authors[0]["last"]
            elif len(authors) == 2:
                author_text = (
                    f"{authors[0]['last']} & {authors[1]['last']}"
                )
            else:
                author_text = f"{authors[0]['last']} et al."

            if len(title) > 100:
                title = title[:97] + "..."

            row = BoxLayout(
                orientation="horizontal",
                size_hint_y=None,
                height=65,
                spacing=5
            )

            checkbox = CheckBox(
                size_hint_x=None,
                width=45
            )
            checkbox.bind(
                active=lambda cb, active, n=number:
                self.select_result(n, active)
            )

            label = Label(
                text=f"{author_text} ({year}) — {title}",
                halign="left",
                valign="middle"
            )
            label.bind(
                size=lambda obj, size:
                setattr(obj, "text_size", size)
            )

            row.add_widget(checkbox)
            row.add_widget(label)

            self.results_box.add_widget(row)

    def select_result(self, number, active):
        if active:
            if number not in self.selected:
                self.selected.append(number)
        else:
            if number in self.selected:
                self.selected.remove(number)

        self.selected.sort()

    def selected_items(self):
        return [
            self.results[number]
            for number in self.selected
            if number < len(self.results)
        ]

    def copy_citation(self, instance):
        items = self.selected_items()

        if not items:
            self.status.text = "Select at least one reference."
            return

        citations = [
            apa_in_text(item.get("data", {}))
            for item in items
        ]

        text = "; ".join(citations)
        Clipboard.copy(text)
        self.status.text = "In-text citation copied to clipboard."

    def copy_reference(self, instance):
        items = self.selected_items()

        if not items:
            self.status.text = "Select at least one reference."
            return

        references = [
            apa_full_reference(item.get("data", {}))
            for item in items
        ]

        text = "\n\n".join(references)
        Clipboard.copy(text)
        self.status.text = "APA 7 reference(s) copied to clipboard."

    def copy_narrative(self, instance):
        items = self.selected_items()

        if not items:
            self.status.text = "Select at least one reference."
            return

        citations = [
            apa_narrative(item.get("data", {}))
            for item in items
        ]

        text = "; ".join(citations)
        Clipboard.copy(text)
        self.status.text = "Narrative citation copied to clipboard."

    def clear_results(self, instance):
        self.results = []
        self.selected = []
        self.results_box.clear_widgets()
        self.status.text = "Cleared."

    @staticmethod
    def get_authors(data):
        authors = []

        for creator in data.get("creators", []):
            if creator.get("creatorType") not in (None, "author"):
                continue

            if creator.get("lastName"):
                authors.append({
                    "first": creator.get("firstName", "").strip(),
                    "last": creator.get("lastName", "").strip()
                })
            elif creator.get("name"):
                authors.append({
                    "first": "",
                    "last": creator.get("name", "").strip()
                })

        return authors

    @staticmethod
    def get_year(data):
        import re

        date = data.get("date", "") or ""
        match = re.search(r"\b(19|20)\d{2}\b", date)

        if match:
            return match.group(0)

        return "n.d."


if __name__ == "__main__":
    ZoteroApp().run()
