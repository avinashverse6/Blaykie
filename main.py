import json
import os
import threading

from kivy.app import App
from kivy.clock import Clock
from kivy.core.clipboard import Clipboard
from kivy.core.window import Window
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.checkbox import CheckBox
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput

from zotero_engine import (
    get_authors,
    get_year,
    multiple_in_text,
    narrative_text,
    references_text,
    search_zotero,
)

APP_TITLE = "Blaykie"

# Keeps the text boxes visible when the phone keyboard opens
Window.softinput_mode = "below_target"


class ZoteroApp(App):

    def build(self):
        self.results = []
        self.selected = []
        self.config_path = os.path.join(self.user_data_dir, "credentials.json")

        root = BoxLayout(orientation="vertical", padding=15, spacing=10)

        root.add_widget(Label(
            text=APP_TITLE,
            font_size="24sp",
            size_hint_y=None,
            height=45,
        ))

        credentials = GridLayout(
            cols=2, spacing=8, size_hint_y=None, height=100
        )
        credentials.add_widget(Label(text="User ID"))
        self.user_id = TextInput(multiline=False, hint_text="Zotero User ID")
        credentials.add_widget(self.user_id)

        credentials.add_widget(Label(text="API Key"))
        self.api_key = TextInput(
            multiline=False, password=True, hint_text="Zotero API Key"
        )
        credentials.add_widget(self.api_key)
        root.add_widget(credentials)

        search_row = BoxLayout(size_hint_y=None, height=50, spacing=8)
        self.search_input = TextInput(
            multiline=False, hint_text="Search Zotero..."
        )
        self.search_input.bind(on_text_validate=self.do_search)
        search_row.add_widget(self.search_input)

        self.search_button = Button(text="SEARCH")
        self.search_button.bind(on_press=self.do_search)
        search_row.add_widget(self.search_button)
        root.add_widget(search_row)

        self.status = Label(
            text="Enter your Zotero credentials and search.",
            size_hint_y=None,
            height=35,
        )
        root.add_widget(self.status)

        scroll = ScrollView()
        self.results_box = GridLayout(cols=1, spacing=5, size_hint_y=None)
        self.results_box.bind(
            minimum_height=self.results_box.setter("height")
        )
        scroll.add_widget(self.results_box)
        root.add_widget(scroll)

        action_row = GridLayout(
            cols=2, spacing=8, size_hint_y=None, height=105
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

        self.load_credentials()
        return root

    # ---------- keep the app alive when switching apps ----------

    def on_pause(self):
        return True

    def on_resume(self):
        pass

    # ---------- saved credentials ----------

    def load_credentials(self):
        try:
            with open(self.config_path, "r", encoding="utf-8") as file:
                saved = json.load(file)
            self.user_id.text = saved.get("user_id", "")
            self.api_key.text = saved.get("api_key", "")
        except (OSError, ValueError):
            pass

    def save_credentials(self, user_id, api_key):
        try:
            with open(self.config_path, "w", encoding="utf-8") as file:
                json.dump({"user_id": user_id, "api_key": api_key}, file)
        except OSError:
            pass

    # ---------- searching (runs in background so the app never freezes) ----------

    def do_search(self, instance):
        user_id = self.user_id.text.strip()
        api_key = self.api_key.text.strip()
        query = self.search_input.text.strip()

        if not user_id or not api_key:
            self.status.text = "Please enter Zotero User ID and API Key."
            return
        if not user_id.isdigit():
            self.status.text = "User ID must be numbers only (not your username)."
            return
        if not query:
            self.status.text = "Please enter a search term."
            return

        self.status.text = "Searching Zotero..."
        self.search_button.disabled = True
        self.results_box.clear_widgets()
        self.results = []
        self.selected = []

        threading.Thread(
            target=self.search_worker,
            args=(query, user_id, api_key),
            daemon=True,
        ).start()

    def search_worker(self, query, user_id, api_key):
        try:
            results = search_zotero(query, user_id, api_key)
        except Exception as error:
            message = str(error) or "Something went wrong."
            Clock.schedule_once(lambda dt, m=message: self.search_failed(m))
            return
        Clock.schedule_once(
            lambda dt, r=results: self.show_results(r, user_id, api_key)
        )

    def search_failed(self, message):
        self.search_button.disabled = False
        self.status.text = message

    def show_results(self, results, user_id, api_key):
        self.search_button.disabled = False
        self.results = results

        if not results:
            self.status.text = "No references found."
            return

        self.save_credentials(user_id, api_key)
        self.status.text = f"{len(results)} result(s) found."

        for number, item in enumerate(results):
            data = item.get("data", {})
            title = data.get("title") or "[No title]"
            year = get_year(data)
            authors = get_authors(data)

            if len(authors) == 0:
                author_text = "Unknown author"
            elif len(authors) == 1:
                author_text = authors[0]["last"]
            elif len(authors) == 2:
                author_text = f"{authors[0]['last']} & {authors[1]['last']}"
            else:
                author_text = f"{authors[0]['last']} et al."

            if len(title) > 100:
                title = title[:97] + "..."

            row = BoxLayout(
                orientation="horizontal",
                size_hint_y=None,
                height=65,
                spacing=5,
            )

            checkbox = CheckBox(size_hint_x=None, width=45)
            checkbox.bind(
                active=lambda cb, active, n=number:
                self.select_result(n, active)
            )

            label = Label(
                text=f"{author_text} ({year}) — {title}",
                halign="left",
                valign="middle",
            )
            # Wrap text to the label width and grow the row to fit it,
            # so long titles never overlap the next result
            label.bind(
                width=lambda obj, w: setattr(obj, "text_size", (w, None))
            )
            label.bind(
                texture_size=lambda obj, ts, r=row:
                setattr(r, "height", max(65, ts[1] + 16))
            )

            row.add_widget(checkbox)
            row.add_widget(label)
            self.results_box.add_widget(row)

    # ---------- selection and copying ----------

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
        Clipboard.copy(multiple_in_text(items))
        self.status.text = "In-text citation copied to clipboard."

    def copy_reference(self, instance):
        items = self.selected_items()
        if not items:
            self.status.text = "Select at least one reference."
            return
        Clipboard.copy(references_text(items))
        self.status.text = "APA 7 reference(s) copied to clipboard."

    def copy_narrative(self, instance):
        items = self.selected_items()
        if not items:
            self.status.text = "Select at least one reference."
            return
        Clipboard.copy(narrative_text(items))
        self.status.text = "Narrative citation copied to clipboard."

    def clear_results(self, instance):
        self.results = []
        self.selected = []
        self.results_box.clear_widgets()
        self.status.text = "Cleared."


if __name__ == "__main__":
    ZoteroApp().run()
