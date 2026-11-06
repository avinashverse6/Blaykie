"""Blaykie Lab: the DOCX citation workflow.

Open a .docx, find the spot, tap or select it, search your Zotero library,
insert the citation, then export a NEW .docx with the APA 7 reference list.
The original file is never changed.
"""

import os
import time

os.environ.setdefault("KIVY_NO_CONSOLELOG", "1")     # less work on a slow phone
T_START = time.perf_counter()

import json
import shutil
import threading
import traceback

from kivy.app import App
from kivy.clock import Clock
from kivy.core.clipboard import Clipboard
from kivy.core.window import Window
from kivy.metrics import dp, sp
from kivy.uix.behaviors import ButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.modalview import ModalView
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput
from kivy.utils import escape_markup, platform

from theme import configure_theme, is_dark, resolve_color
from ui import (
    ACCENT_TEXT, BG, ERROR, LINE, MUTED, SURFACE, SURFACE2, TEXT,
    GearButton, InputBox, Logo, PillButton, ResultCard, col, paint, small_label,
)

Window.clearcolor = col(BG)
Window.softinput_mode = "resize"      # the pop-ups shrink above the keyboard instead of hiding behind it

IMPORT_ERRORS = []
try:
    from blaykie_docx import (
        DocxProject, MissingReferences, NotEditable, build_citation_text,
    )
    from zotero_engine import (
        fetch_items_by_keys, get_authors, get_year, search_zotero,
    )
except Exception:                                    # pragma: no cover
    DocxProject = None
    NotEditable = MissingReferences = Exception
    IMPORT_ERRORS.append(traceback.format_exc(limit=3))

Chooser = SharedStorage = ShareSheet = None
api_version = 0
request_permissions = Permission = None
if platform == "android":
    try:
        from android import api_version
        from android.permissions import Permission, request_permissions
    except Exception:
        IMPORT_ERRORS.append("android: " + traceback.format_exc(limit=2))
    try:
        from androidstorage4kivy import Chooser, ShareSheet, SharedStorage
    except Exception:
        IMPORT_ERRORS.append("storage: " + traceback.format_exc(limit=2))

LOGO_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.png")

CHUNK = 10          # paragraphs drawn at a time while you scroll
SEARCH_LIMIT = 60   # references fetched per Zotero search
SEARCH_SHOWN = 20   # references drawn at first; "Show more" adds more

PERF = []           # (step, milliseconds) shown in the Speed log


class timed:
    """Measures one step for the Speed log on the home screen."""

    def __init__(self, name):
        self.name = name

    def __enter__(self):
        self.t = time.perf_counter()
        return self

    def __exit__(self, *exc):
        PERF.append((self.name, (time.perf_counter() - self.t) * 1000))
        del PERF[:-80]
        return False


SHEETS = []         # pop-ups that are open (for the Back button)


def panel_view(height_hint=0.94):
    """A rounded pop-up with a dark panel inside. Returns (view, panel)."""
    view = ModalView(
        size_hint=(0.96, height_hint), auto_dismiss=False,
        background="", background_color=(0, 0, 0, 0),
        overlay_color=(0, 0, 0, 0.75),
    )
    try:
        view.anim_duration = 0          # open and close at once
    except Exception:
        pass
    view.bind(on_open=lambda v: SHEETS.append(v) if v not in SHEETS else None,
              on_dismiss=lambda v: SHEETS.remove(v) if v in SHEETS else None)
    panel = BoxLayout(orientation="vertical", padding=dp(16), spacing=dp(8))
    paint(panel, SURFACE, [dp(24)] * 4, edge_hex=LINE)
    view.add_widget(panel)
    return view, panel


def wrapped_label(text, color=TEXT, size=14, height=None):
    label = Label(text=text, color=col(color), font_size=sp(size),
                  halign="left", valign="top", size_hint_y=None,
                  height=dp(height or 24))
    label.bind(width=lambda w, v: setattr(w, "text_size", (v, None)))
    if height is None:
        label.bind(texture_size=lambda w, v: setattr(w, "height", v[1] + dp(4)))
    return label


def fit_panel(view, panel):
    """Make the pop-up exactly as tall as its content (no empty space)."""
    total = sum(child.height for child in panel.children)
    total += panel.spacing * (len(panel.children) - 1) + dp(32)
    view.size_hint_y = None
    view.height = total


class BlockCard(ButtonBehavior, BoxLayout):
    """One paragraph / heading / table of the document."""

    def __init__(self, text, kind, on_tap, **kwargs):
        super().__init__(orientation="vertical", size_hint_y=None,
                         height=dp(56), padding=[dp(14), dp(10)], **kwargs)
        self.on_tap = on_tap
        heading = kind.startswith("heading")
        paint(self, SURFACE2 if heading else SURFACE, [dp(14)], edge_hex=LINE)
        self.label = Label(
            text=text, halign="left", valign="top", size_hint_y=None,
            height=dp(30), bold=heading, font_size=sp(15 if heading else 14),
            color=col(ACCENT_TEXT if heading else (TEXT if kind == "paragraph" else MUTED)),
        )
        self.label.bind(width=lambda w, v: setattr(w, "text_size", (v, None)),
                        texture_size=self.fit)
        self.add_widget(self.label)

    def fit(self, label, size):
        label.height = size[1]
        self.height = size[1] + dp(20)

    def on_release(self):
        self.on_tap()


class ParaInput(TextInput):
    """The paragraph box. Cut / copy / paste / delete work in it. The keyboard
    stays closed until the Keyboard button is pressed."""

    def insert_text(self, substring, from_undo=False):
        for bad in ("\r\n", "\n", "\r", "\t"):
            substring = substring.replace(bad, " ")
        return super().insert_text(substring, from_undo=from_undo)


class LabApp(App):

    # ------------------------------------------------------------------
    #  start-up
    # ------------------------------------------------------------------

    def build(self):
        self.project = None
        self.visible = []
        self.cards = {}
        self.loaded = 0
        self.loading = False
        self.doc_name = ""
        self.shared_output = None
        self.sharer = None
        self.chooser = None
        self.docs_dir = os.path.join(self.user_data_dir, "docs")
        os.makedirs(self.docs_dir, exist_ok=True)
        self.state_path = os.path.join(self.user_data_dir, "state.json")
        self.config_path = os.path.join(self.user_data_dir, "credentials.json")
        self.appearance_path = os.path.join(self.user_data_dir, "appearance.json")
        self.theme_mode = self.load_theme_mode()
        configure_theme(self.theme_mode, self.system_theme_is_dark())
        Window.clearcolor = col(BG)

        self._save_event = None
        self.cards = {}
        self.loaded = 0
        self.loading = False
        Window.bind(on_keyboard=self.on_key)
        self.root_box = BoxLayout(orientation="vertical")
        self.build_settings()
        self.load_credentials()
        self.show_home()
        return self.root_box

    def on_start(self):
        PERF.append(("start: imports + first screen", (time.perf_counter() - T_START) * 1000))
        for message in IMPORT_ERRORS:
            self.home_status.text = "Problem: " + message[-300:]
        if platform == "android":
            if Chooser is not None:
                self.chooser = Chooser(self.on_chosen)
                self.sharer = ShareSheet() if ShareSheet else None
            if request_permissions and api_version and api_version < 29:
                request_permissions([Permission.WRITE_EXTERNAL_STORAGE,
                                     Permission.READ_EXTERNAL_STORAGE])

    def on_pause(self):
        self.save_state()
        return True

    def on_stop(self):
        self.save_state()

    def on_key(self, window, key, *args):
        """Android Back: close the pop-up on top, then the document, then leave."""
        if key != 27:
            return False
        if SHEETS:
            top = SHEETS[-1]
            if top is getattr(self, "para_view", None):
                self.done_paragraph()
            else:
                top.dismiss()
            return True
        if self.project and self.screen == "doc":
            self.close_doc()
            return True
        return False

    def on_resume(self):
        if self.theme_mode != "system":
            return
        previous = is_dark()
        configure_theme(self.theme_mode, self.system_theme_is_dark())
        if previous != is_dark():
            self.refresh_for_theme()

    def system_theme_is_dark(self):
        """Read Android's current UI_MODE_NIGHT flag; default to light elsewhere."""
        if platform != "android":
            return False
        try:
            from jnius import autoclass
            activity = autoclass("org.kivy.android.PythonActivity").mActivity
            ui_mode = activity.getResources().getConfiguration().uiMode
            return (ui_mode & 0x30) == 0x20  # UI_MODE_NIGHT_YES
        except Exception:
            return False

    def load_theme_mode(self):
        try:
            with open(self.appearance_path, "r", encoding="utf-8") as file:
                mode = json.load(file).get("theme", "system")
            return mode if mode in ("light", "dark", "system") else "system"
        except (OSError, ValueError, AttributeError):
            return "system"

    def persist_theme_mode(self):
        try:
            with open(self.appearance_path, "w", encoding="utf-8") as file:
                json.dump({"theme": self.theme_mode}, file)
        except OSError:
            pass

    def select_theme(self, mode):
        """Persist and apply a selection immediately, preserving settings inputs."""
        user_id, api_key = self.user_id.text, self.api_key.text
        self.settings_view.dismiss()
        self.theme_mode = mode
        configure_theme(mode, self.system_theme_is_dark())
        self.persist_theme_mode()
        Window.clearcolor = col(BG)
        self.show_home()
        self.build_settings()
        self.user_id.text, self.api_key.text = user_id, api_key
        self.settings_view.open()

    def refresh_for_theme(self):
        """Recreate the active screen so every canvas and widget uses new colors."""
        Window.clearcolor = col(BG)
        if self.screen == "doc" and self.project:
            scroll_y = getattr(getattr(self, "cards_scroll", None), "scroll_y", 1)
            self.show_doc()
            Clock.schedule_once(lambda dt: setattr(self.cards_scroll, "scroll_y", scroll_y), 0)
        else:
            was_settings_open = getattr(self, "settings_view", None) in SHEETS
            credentials = (self.user_id.text, self.api_key.text) if was_settings_open else None
            if was_settings_open:
                self.settings_view.dismiss()
            self.show_home()
            if was_settings_open:
                self.build_settings()
                self.user_id.text, self.api_key.text = credentials
                self.settings_view.open()

    # ------------------------------------------------------------------
    #  settings (Zotero details)
    # ------------------------------------------------------------------

    def build_settings(self):
        view, panel = panel_view(0.82)
        panel.add_widget(small_label("Appearance", color=TEXT, size=18,
                                     bold=True, height=30))
        theme_row = BoxLayout(size_hint_y=None, height=dp(42), spacing=dp(6))
        for mode, title in (("light", "Light"), ("dark", "Dark"),
                            ("system", "System Default")):
            button = PillButton(
                text=title,
                style="primary" if mode == self.theme_mode else "secondary",
                font_size=sp(12),
            )
            button.bind(on_release=lambda *args, selected=mode: self.select_theme(selected))
            theme_row.add_widget(button)
        panel.add_widget(theme_row)
        panel.add_widget(small_label("Zotero account", color=TEXT, size=18,
                                     bold=True, height=32))
        panel.add_widget(wrapped_label(
            "Find your User ID and API key at zotero.org/settings/keys "
            "(read access is enough).", MUTED, 12, 44))
        panel.add_widget(small_label("User ID", bold=True))
        id_box = InputBox(hint="Numbers only")
        self.user_id = id_box.input
        panel.add_widget(id_box)
        panel.add_widget(small_label("API Key", bold=True))
        key_box = InputBox(hint="Paste your API key", password=True)
        self.api_key = key_box.input
        panel.add_widget(key_box)
        self.settings_message = small_label("", color=ERROR, size=12, height=22)
        panel.add_widget(self.settings_message)
        speed = PillButton(text="Speed log", style="ghost", size_hint_y=None,
                           height=dp(40), font_size=sp(12))
        speed.bind(on_release=lambda *a: (view.dismiss(), self.open_speed_log()))
        panel.add_widget(speed)
        row = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(10))
        cancel = PillButton(text="Cancel", style="ghost")
        cancel.bind(on_release=lambda *a: view.dismiss())
        save = PillButton(text="Save", style="primary")
        save.bind(on_release=self.save_settings)
        row.add_widget(cancel)
        row.add_widget(save)
        panel.add_widget(row)
        fit_panel(view, panel)
        self.settings_view = view

    def open_settings(self, *args):
        self.settings_message.text = ""
        self.settings_view.open()

    def save_settings(self, *args):
        user_id, api_key = self.user_id.text.strip(), self.api_key.text.strip()
        if not user_id.isdigit():
            self.settings_message.text = "User ID must be numbers only."
            return
        if not api_key:
            self.settings_message.text = "Please enter your API key."
            return
        try:
            with open(self.config_path, "w", encoding="utf-8") as file:
                json.dump({"user_id": user_id, "api_key": api_key}, file)
        except OSError:
            pass
        self.settings_view.dismiss()

    def load_credentials(self):
        try:
            with open(self.config_path, "r", encoding="utf-8") as file:
                saved = json.load(file)
            self.user_id.text = saved.get("user_id", "")
            self.api_key.text = saved.get("api_key", "")
        except (OSError, ValueError):
            pass

    def credentials(self):
        user_id, api_key = self.user_id.text.strip(), self.api_key.text.strip()
        if not user_id.isdigit() or not api_key:
            return None
        return user_id, api_key

    # ------------------------------------------------------------------
    #  home
    # ------------------------------------------------------------------

    def show_home(self):
        self.screen = "home"
        self.root_box.clear_widgets()
        area = BoxLayout(orientation="vertical", padding=[dp(22), dp(36), dp(22), dp(28)],
                         spacing=dp(14))

        header = BoxLayout(size_hint_y=None, height=dp(64), spacing=dp(12))
        if os.path.exists(LOGO_FILE):
            header.add_widget(Logo(LOGO_FILE, size_hint=(None, None),
                                   size=(dp(52), dp(52)), pos_hint={"center_y": 0.5}))
        title = Label(
            text=f"[size=26sp][b]Blaykie[/b][/size]\n[size=12sp][color={resolve_color(MUTED)}]"
                 "Cite smarter. APA 7 in seconds.[/color][/size]",
            markup=True, color=col(TEXT), halign="left", valign="middle")
        title.bind(size=lambda w, v: setattr(w, "text_size", v))
        header.add_widget(title)
        gear = GearButton(size_hint=(None, None), size=(dp(46), dp(46)),
                          pos_hint={"center_y": 0.5})
        gear.bind(on_release=self.open_settings)
        header.add_widget(gear)
        area.add_widget(header)
        area.add_widget(Label(size_hint_y=None, height=dp(6)))

        open_btn = PillButton(
            text="[b]Open a .docx[/b]\n[size=12sp]Choose a Word file from your phone[/size]",
            markup=True, style="primary", size_hint_y=None, height=dp(88),
            font_size=sp(18))
        open_btn.bind(on_release=self.choose)
        area.add_widget(open_btn)

        saved = self.read_state()
        if saved:
            name = os.path.basename(saved["path"])[:34]
            resume = PillButton(
                text="[b]Continue[/b]\n[size=12sp]" + escape_markup(name) + "[/size]",
                markup=True, style="secondary", size_hint_y=None, height=dp(76),
                font_size=sp(16))
            resume.bind(on_release=self.resume)
            area.add_widget(resume)

        tips = BoxLayout(orientation="vertical", size_hint_y=None, height=dp(136),
                         padding=dp(16), spacing=dp(4))
        paint(tips, SURFACE, [dp(18)], edge_hex=LINE)
        tips.add_widget(small_label("How it works", color=TEXT, size=13, bold=True, height=22))
        for line in ("1   Open your Word file", "2   Tap where a citation goes",
                     "3   Pick it from Zotero, then export"):
            tips.add_widget(small_label(line, color=MUTED, size=12, height=22))
        area.add_widget(tips)

        self.home_status = wrapped_label("", MUTED, 13)
        area.add_widget(self.home_status)
        area.add_widget(Label())
        self.root_box.add_widget(area)

    def open_speed_log(self, *args):
        """How long each step took on this phone. Copy it and send it to find slow parts."""
        view, panel = panel_view(0.85)
        panel.add_widget(small_label("Speed log", color=TEXT, size=18, bold=True, height=30))
        lines = [f"{ms:7.0f} ms   {name}" for name, ms in PERF] or ["Nothing recorded yet."]
        scroll = ScrollView(bar_width=dp(3))
        label = wrapped_label("\n".join(lines), TEXT, 12)
        scroll.add_widget(label)
        panel.add_widget(scroll)
        row = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(10))
        copy = PillButton(text="Copy", style="secondary")
        copy.bind(on_release=lambda *a: Clipboard.copy("\n".join(lines)))
        close = PillButton(text="Close", style="primary")
        close.bind(on_release=lambda *a: view.dismiss())
        row.add_widget(copy)
        row.add_widget(close)
        panel.add_widget(row)
        view.open()

    # ------------------------------------------------------------------
    #  opening a document
    # ------------------------------------------------------------------

    def choose(self, *args):
        try:
            self.chooser.choose_content("*/*")
        except Exception:
            self.home_status.text = "Could not open the file picker."

    def on_chosen(self, uri_list):
        Clock.schedule_once(lambda dt: self.handle_chosen(uri_list))

    def handle_chosen(self, uri_list):
        try:
            if not uri_list:
                return
            path = SharedStorage().copy_from_shared(uri_list[0])
            if not path:
                self.home_status.text = "Could not read that file."
                return
            self.open_project(path)
        except Exception:
            self.home_status.text = "Could not open it: " + traceback.format_exc(limit=1)[-200:]

    def open_project(self, path):
        name = os.path.basename(path)
        if not name.lower().endswith(".docx"):
            self.home_status.text = "Please choose a .docx file."
            return
        target = os.path.join(self.docs_dir, name)
        if os.path.abspath(path) != os.path.abspath(target):
            shutil.copy(path, target)
        try:
            project = DocxProject(target)
        except Exception:
            self.home_status.text = "This file could not be read as a Word document."
            return
        self.project = project
        self.doc_name = name
        self.shared_output = None
        self.save_state()
        with timed("open document: first screen"):
            self.start_reading()

    def read_state(self):
        try:
            with open(self.state_path, "r", encoding="utf-8") as file:
                state = json.load(file)
            if os.path.exists(state["path"]):
                return state
        except (OSError, ValueError, KeyError):
            pass
        return None

    def request_save(self):
        """Save shortly after the last change instead of after every tap."""
        if self._save_event is not None:
            Clock.unschedule(self._save_event)
        self._save_event = Clock.schedule_once(lambda dt: self.save_state(), 1.5)

    def save_state(self):
        if self._save_event is not None:
            Clock.unschedule(self._save_event)
            self._save_event = None
        if not self.project:
            return
        try:
            with open(self.state_path, "w", encoding="utf-8") as file:
                json.dump({"path": self.project.path, "ops": self.project.ops}, file)
        except OSError:
            pass

    def resume(self, *args):
        state = self.read_state()
        if not state:
            return
        try:
            project = DocxProject(state["path"])
            project.load_ops(state["ops"])
        except Exception:
            self.home_status.text = "Could not restore the last session."
            return
        self.project = project
        self.doc_name = os.path.basename(state["path"])
        self.start_reading()

    def start_reading(self):
        with timed("open document: list paragraphs"):
            self.visible = self.project.visible_blocks()
        self.show_doc()

    # ------------------------------------------------------------------
    #  reading view
    # ------------------------------------------------------------------

    def show_doc(self):
        self.screen = "doc"
        root = self.root_box
        root.clear_widgets()

        bar = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(8),
                        padding=[dp(10), dp(8), dp(10), 0])
        for text, handler, style in (
            ("Close", self.close_doc, "ghost"),
            ("Undo", self.undo, "secondary"),
            ("Export", self.open_export, "primary"),
        ):
            button = PillButton(text=text, style=style, font_size=sp(13))
            button.bind(on_release=handler)
            bar.add_widget(button)
        root.add_widget(bar)

        self.info_label = small_label("", color=MUTED, size=12, height=26)
        info_row = BoxLayout(size_hint_y=None, height=dp(26), padding=[dp(14), 0])
        info_row.add_widget(self.info_label)
        root.add_widget(info_row)

        scroll = ScrollView(bar_width=dp(3))
        self.cards_box = GridLayout(cols=1, spacing=dp(8), size_hint_y=None,
                                    padding=[dp(10), dp(4), dp(10), dp(12)])
        self.cards_box.bind(minimum_height=self.cards_box.setter("height"))
        scroll.add_widget(self.cards_box)
        scroll.bind(scroll_y=self.on_scroll)
        self.cards_scroll = scroll
        root.add_widget(scroll)
        root.add_widget(Label(size_hint_y=None, height=dp(28)))     # keeps clear of the navigation bar

        self.cards = {}
        self.loaded = 0
        self.loading = False
        self.add_cards(CHUNK)
        Clock.schedule_once(self.fill_screen, 0.4)

    def display_text(self, block):
        text = self.project.block_text(block).strip()
        if self.project.block_kind(block) == "table":
            text = "[Table] " + text
        return text

    def update_info(self):
        cites = len(self.project.citations())
        self.info_label.text = f"{self.doc_name[:34]}  |  {cites} citation(s) added"

    def make_card(self, block):
        card = BlockCard(self.display_text(block), self.project.block_kind(block),
                         lambda b=block: self.open_block(b))
        card.block = block
        return card

    def add_cards(self, count):
        """Draw the next paragraphs at the bottom."""
        end = min(len(self.visible), self.loaded + count)
        with timed(f"draw {end - self.loaded} paragraphs"):
            for block in self.visible[self.loaded:end]:
                card = self.make_card(block)
                self.cards[block] = card
                self.cards_box.add_widget(card)
        self.loaded = end
        self.update_info()

    def current_offset(self):
        scroll, box = self.cards_scroll, self.cards_box
        return (1 - scroll.scroll_y) * max(1.0, box.height - scroll.height)

    def keep_scroll_position(self, offset):
        """More paragraphs were added below: stay on the same paragraph. On a
        slow phone the new paragraphs measure themselves a little later, so
        check again until the height stops changing."""
        state = {"tries": 0, "last": -1}

        def restore(dt):
            scroll, box = self.cards_scroll, self.cards_box
            room = max(1.0, box.height - scroll.height)
            scroll.scroll_y = max(0.0, min(1.0, 1 - offset / room))
            state["tries"] += 1
            if box.height != state["last"] and state["tries"] < 8:
                state["last"] = box.height
                Clock.schedule_once(restore, 0.1)
            else:
                self.loading = False

        Clock.schedule_once(restore, 0.1)

    def on_scroll(self, scroll, value):
        """About a screen or two from the end: draw the next paragraphs."""
        if self.loading or self.loaded >= len(self.visible):
            return
        room = max(1.0, self.cards_box.height - scroll.height)
        if value * room < dp(1000):                 # pixels still below the screen
            self.loading = True
            offset = self.current_offset()
            self.add_cards(CHUNK)
            self.keep_scroll_position(offset)

    def fill_screen(self, dt):
        """A short document must still fill the screen."""
        if self.screen != "doc" or self.loaded >= len(self.visible):
            return
        if self.cards_box.height <= self.cards_scroll.height * 1.5:
            self.add_cards(CHUNK)
            Clock.schedule_once(self.fill_screen, 0.4)

    def refresh_block(self, block):
        card = self.cards.get(block)
        if card is not None:
            card.label.text = self.display_text(block)
        self.update_info()

    def refresh_all(self):
        """After Undo: update the text of what is drawn, nothing is rebuilt."""
        with timed("refresh after undo"):
            for block, card in self.cards.items():
                card.label.text = self.display_text(block)
        self.update_info()

    def close_doc(self, *args):
        self.save_state()
        self.show_home()

    def undo(self, *args):
        if self.project and self.project.undo():
            self.request_save()
            self.refresh_all()
        else:
            self.info_label.text = "Nothing to undo."

    # ------------------------------------------------------------------
    #  picking the spot
    # ------------------------------------------------------------------

    def open_block(self, block):
        with timed("open paragraph box"):
            self.build_paragraph_box(block)

    def build_paragraph_box(self, block):
        project = self.project
        if not project.block_editable(block):
            self.info_label.text = "Tables and special blocks cannot be edited."
            return
        view, panel = panel_view(0.94)
        panel.add_widget(small_label(f"Paragraph {block}", color=TEXT, size=18,
                                     bold=True, height=30))

        row = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(8))
        done_btn = PillButton(text="Done", style="secondary", font_size=sp(13))
        done_btn.bind(on_release=self.done_paragraph)
        self.keyboard_btn = PillButton(text="Keyboard", style="secondary", font_size=sp(13))
        self.keyboard_btn.bind(on_release=self.toggle_keyboard)
        insert_btn = PillButton(text="Insert citation", style="primary", font_size=sp(13))
        insert_btn.bind(on_release=self.insert_here)
        for widget in (done_btn, self.keyboard_btn, insert_btn):
            row.add_widget(widget)
        panel.add_widget(row)

        panel.add_widget(wrapped_label(
            "Tap where the citation should go. Hold to select, then use "
            "Cut, Copy or Paste. Blaykie never changes your text by itself.",
            MUTED, 12, 46))

        field = ParaInput(
            text=project.block_text(block), multiline=True, keyboard_mode="managed",
            background_normal="", background_active="",
            background_color=col(SURFACE2), foreground_color=col(TEXT),
            cursor_color=col(ACCENT_TEXT), selection_color=col(ACCENT, 0.45),
            font_size=sp(16), padding=[dp(12), dp(12)],
        )
        panel.add_widget(field)
        self.para_view, self.para_field, self.para_block = view, field, block
        self.keyboard_on = False
        view.bind(on_dismiss=self.release_field)

        message = small_label("", color=ERROR, size=12, height=22)
        self.para_message = message
        panel.add_widget(message)
        view.open()

    def toggle_keyboard(self, *args):
        field = self.para_field
        try:
            if self.keyboard_on:
                field.hide_keyboard()
                self.keyboard_on = False
                self.keyboard_btn.text = "Keyboard"
            else:
                field.focus = True
                field.show_keyboard()
                self.keyboard_on = True
                self.keyboard_btn.text = "Hide keyboard"
        except Exception:
            self.para_message.text = "Could not change the keyboard."

    def release_field(self, *args):
        """Hide Android's selection handles, menus and keyboard when the box closes."""
        field = getattr(self, "para_field", None)
        if field is None:
            return
        try:
            field.cancel_selection()
            field.hide_keyboard()
            field.focus = False
            for name in ("_hide_handles", "_hide_cut_copy_paste"):
                hide = getattr(field, name, None)
                if hide:
                    hide(Window)
        except Exception:
            pass

    def sync_paragraph(self):
        """Apply what the user did with Cut / Delete / Paste to the document.
        Returns False (and puts the box back) if that part is protected."""
        field = self.para_field
        try:
            changed = self.project.apply_text_edit(self.para_block, field.text)
        except NotEditable as error:
            field.text = self.project.block_text(self.para_block)
            self.para_message.text = str(error)[:110]
            return False
        if changed:
            self.request_save()
            self.refresh_block(self.para_block)
        self.para_message.text = ""
        return True

    def close_paragraph(self, *args):
        self.release_field()
        self.para_view.dismiss()

    def done_paragraph(self, *args):
        if self.sync_paragraph():
            self.close_paragraph()

    def insertion_point(self):
        """Where the citation goes: the cursor. If some text is selected, right
        after the selection. Nothing is ever replaced or removed."""
        field = self.para_field
        length = len(field.text)
        if field.selection_text:
            return min(length, max(field.selection_from, field.selection_to))
        return min(max(0, field.cursor_index()), length)

    def insert_here(self, *args):
        position = self.insertion_point()
        if not self.sync_paragraph():
            return
        position = min(position, len(self.project.block_text(self.para_block)))
        self.open_citation_sheet(self.para_block, position, position)

    # ------------------------------------------------------------------
    #  choosing the Zotero reference
    # ------------------------------------------------------------------

    def open_citation_sheet(self, block, start, end):
        if not self.credentials():
            self.para_message.text = "Add your Zotero details first (Home > Zotero settings)."
            return
        view, panel = panel_view(0.96)
        self.cite_view = view
        self.cite_target = (block, start, end)
        self.cite_results = []
        self.cite_selected = []
        self.cite_style = "parenthetical"

        panel.add_widget(small_label("Insert citation", color=TEXT, size=17,
                                     bold=True, height=28))
        text = self.project.block_text(block)
        before = escape_markup(text[max(0, start - 36):start])
        after = escape_markup(text[start:start + 36])
        where = Label(
            text=f"[color={resolve_color(MUTED)}]...{before}[/color]"
                 f"[b][color={resolve_color(ACCENT_TEXT)}] | [/color][/b]"
                 f"[color={resolve_color(MUTED)}]{after}...[/color]",
            markup=True, font_size=sp(12), halign="left", valign="top",
            size_hint_y=None, height=dp(44))
        where.bind(width=lambda w, v: setattr(w, "text_size", (v, None)))
        panel.add_widget(where)

        row = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(8))
        box = InputBox(hint="Search your Zotero library")
        self.cite_box = box
        go = PillButton(text="Search", style="primary", size_hint=(None, None),
                        size=(dp(96), dp(52)))
        go.bind(on_release=self.cite_search)
        box.input.bind(on_text_validate=self.cite_search)
        row.add_widget(box)
        row.add_widget(go)
        panel.add_widget(row)

        self.cite_status = small_label("", color=MUTED, size=12, height=22)
        panel.add_widget(self.cite_status)

        scroll = ScrollView(bar_width=dp(3))
        self.cite_grid = GridLayout(cols=1, spacing=dp(8), size_hint_y=None)
        self.cite_grid.bind(minimum_height=self.cite_grid.setter("height"))
        scroll.add_widget(self.cite_grid)
        panel.add_widget(scroll)

        style_row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        self.btn_paren = PillButton(text="(Author, 2020)", style="primary", font_size=sp(12))
        self.btn_narr = PillButton(text="Author (2020)", style="secondary", font_size=sp(12))
        self.btn_paren.bind(on_release=lambda *a: self.set_style("parenthetical"))
        self.btn_narr.bind(on_release=lambda *a: self.set_style("narrative"))
        style_row.add_widget(self.btn_paren)
        style_row.add_widget(self.btn_narr)
        panel.add_widget(style_row)

        extra = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(8))
        self.page_box = InputBox(hint="Page, e.g. 15")
        self.prefix_box = InputBox(hint="Prefix: see")
        for widget in (self.page_box, self.prefix_box):
            widget.input.bind(text=lambda *a: self.update_preview())
            extra.add_widget(widget)
        panel.add_widget(extra)

        self.cite_preview = wrapped_label("", ACCENT_TEXT, 13, 40)
        panel.add_widget(self.cite_preview)
        self.cite_message = small_label("", color=ERROR, size=12, height=22)
        panel.add_widget(self.cite_message)

        buttons = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(10))
        cancel = PillButton(text="Cancel", style="ghost")
        cancel.bind(on_release=lambda *a: view.dismiss())
        insert = PillButton(text="Insert", style="primary")
        insert.bind(on_release=self.confirm_insert)
        buttons.add_widget(cancel)
        buttons.add_widget(insert)
        panel.add_widget(buttons)
        view.open()

    def set_style(self, style):
        self.cite_style = style
        self.btn_paren.style = "primary" if style == "parenthetical" else "secondary"
        self.btn_narr.style = "primary" if style == "narrative" else "secondary"
        self.btn_paren.redraw()
        self.btn_narr.redraw()
        self.update_preview()

    def run_background(self, work, done, failed):
        def target():
            try:
                result = work()
            except Exception as error:
                message = str(error) or "Something went wrong."
                Clock.schedule_once(lambda dt, m=message: failed(m))
                return
            Clock.schedule_once(lambda dt, r=result: done(r))
        threading.Thread(target=target, daemon=True).start()

    def cite_search(self, *args):
        query = self.cite_box.input.text.strip()
        creds = self.credentials()
        if not query:
            self.cite_status.text = "Type something to search."
            return
        self.cite_status.text = "Searching Zotero..."
        self.cite_grid.clear_widgets()
        self.cite_results, self.cite_selected = [], []
        self.run_background(
            lambda: search_zotero(query, creds[0], creds[1], max_results=SEARCH_LIMIT),
            self.show_cite_results,
            lambda message: setattr(self.cite_status, "text", message))

    def show_cite_results(self, results):
        self.cite_results = results
        self.cite_drawn = 0
        self.more_button = None
        self.cite_status.text = f"{len(results)} result(s)" if results else "No references found."
        self.draw_more_results()

    def result_card(self, number):
        item = self.cite_results[number]
        data = item.get("data", {})
        authors = get_authors(data)
        if not authors:
            who = "Unknown author"
        elif len(authors) == 1:
            who = authors[0]["last"]
        elif len(authors) == 2:
            who = f"{authors[0]['last']} & {authors[1]['last']}"
        else:
            who = f"{authors[0]['last']} et al."
        title = (data.get("title") or "[No title]")[:100]
        text = (f"[size=14sp][color={resolve_color(TEXT)}][b]{escape_markup(who)} \u00b7 "
                f"{escape_markup(get_year(data))}[/b][/color][/size]\n{escape_markup(title)}")
        card = ResultCard(text, lambda sel, n=number: self.toggle_item(n, sel))
        if number in self.cite_selected:
            card.set_selected(True, notify=False)
        return card

    def draw_more_results(self, *args):
        """Draw the next few results (a long list at once is slow on a small phone)."""
        grid = self.cite_grid
        if self.more_button is not None:
            grid.remove_widget(self.more_button)
            self.more_button = None
        end = min(len(self.cite_results), self.cite_drawn + SEARCH_SHOWN)
        with timed(f"draw {end - self.cite_drawn} search results"):
            for number in range(self.cite_drawn, end):
                grid.add_widget(self.result_card(number))
        self.cite_drawn = end
        left = len(self.cite_results) - end
        if left > 0:
            button = PillButton(text=f"Show more ({left})", style="secondary",
                                size_hint_y=None, height=dp(48))
            button.bind(on_release=self.draw_more_results)
            self.more_button = button
            grid.add_widget(button)

    def toggle_item(self, number, selected):
        if selected and number not in self.cite_selected:
            self.cite_selected.append(number)
        if not selected and number in self.cite_selected:
            self.cite_selected.remove(number)
        self.update_preview()

    def chosen_items(self):
        return [self.cite_results[n] for n in sorted(self.cite_selected)
                if n < len(self.cite_results)]

    def update_preview(self):
        items = self.chosen_items()
        if not items:
            self.cite_preview.text = "Select one or more references."
            return
        text = build_citation_text(
            items, self.cite_style, self.page_box.input.text.strip(),
            self.prefix_box.input.text.strip())
        self.cite_preview.text = "Will insert:  " + text

    def confirm_insert(self, *args):
        with timed("insert citation"):
            self.do_insert()

    def do_insert(self):
        items = self.chosen_items()
        if not items:
            self.cite_message.text = "Select a reference first."
            return
        block, start, end = self.cite_target
        try:
            self.project.insert_citation(
                block, start, end, items, self.cite_style,
                self.page_box.input.text.strip(), self.prefix_box.input.text.strip())
        except NotEditable as error:
            self.cite_message.text = str(error)[:100]
            return
        self.cite_view.dismiss()
        self.close_paragraph()
        self.request_save()
        self.refresh_block(block)
        self.info_label.text = "Citation added: " + self.project.citations()[-1][2][:50]

    # ------------------------------------------------------------------
    #  export
    # ------------------------------------------------------------------

    def open_export(self, *args):
        project = self.project
        view, panel = panel_view(0.8)
        self.export_view = view
        panel.add_widget(small_label("Export a new .docx", color=TEXT, size=18,
                                     bold=True, height=32))
        lines = [f"Citations added now: {len(project.citations())}",
                 f"Blaykie references in the file: {len(project.present_keys())}"]
        removed, added = project.edit_summary()
        lines.append(f"Your own edits: {removed} character(s) removed, {added} added. "
                     "Citations never remove text.")
        if project.existing_reference_headings() and not project.has_blaykie_bibliography():
            lines.append("Note: the file already has a References heading. "
                         "Blaykie adds its own list at the end.")
        if project.has_tracked_changes:
            lines.append("Note: the file has tracked changes. Citations are inserted as normal text.")
        if project.has_zotero_fields():
            lines.append("Zotero plugin citations are left untouched.")
        panel.add_widget(wrapped_label("\n".join(lines), TEXT, 13, 120))
        panel.add_widget(small_label("References line spacing", bold=True))
        spacing_row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        self.spacing_choice = "match"
        self.spacing_buttons = {}
        for value, text in (("match", "Like my document"), ("1.5", "1.5 lines"),
                            ("double", "Double (APA)")):
            button = PillButton(text=text, font_size=sp(12),
                                style="primary" if value == self.spacing_choice else "secondary")
            button.bind(on_release=lambda *a, v=value: self.set_spacing(v))
            self.spacing_buttons[value] = button
            spacing_row.add_widget(button)
        panel.add_widget(spacing_row)
        self.export_status = wrapped_label("", ACCENT_TEXT, 13, 80)
        panel.add_widget(self.export_status)
        panel.add_widget(Label())

        row = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(10))
        self.export_btn = PillButton(text="Export now", style="primary")
        self.export_btn.bind(on_release=self.run_export)
        self.share_btn = PillButton(text="Share / open", style="secondary", disabled=True)
        self.share_btn.bind(on_release=self.share)
        row.add_widget(self.export_btn)
        row.add_widget(self.share_btn)
        panel.add_widget(row)
        close = PillButton(text="Close", style="ghost", size_hint_y=None, height=dp(48))
        close.bind(on_release=lambda *a: view.dismiss())
        panel.add_widget(close)
        view.open()

    def set_spacing(self, value):
        self.spacing_choice = value
        for name, button in self.spacing_buttons.items():
            button.style = "primary" if name == value else "secondary"
            button.redraw()

    def run_export(self, *args, allow_missing=False):
        project = self.project
        missing = project.missing_keys()
        if missing and not allow_missing:
            creds = self.credentials()
            if not creds:
                self.export_status.text = ("Some earlier citations need their references. "
                                           "Add your Zotero details first.")
                return
            self.export_status.text = f"Getting {len(missing)} reference(s) from Zotero..."
            self.run_background(
                lambda: fetch_items_by_keys(missing, creds[0], creds[1]),
                self.after_fetch,
                lambda message: setattr(self.export_status, "text", message))
            return
        self.finish_export(allow_missing)

    def after_fetch(self, items):
        self.project.register_items(items)
        left = self.project.missing_keys()
        if left:
            self.export_status.text = (f"{len(left)} reference(s) were not found in your "
                                       "library. They will be left out of the list.")
            self.finish_export(True)
            return
        self.finish_export(False)

    def finish_export(self, allow_missing):
        with timed("export"):
            self.do_export(allow_missing)

    def do_export(self, allow_missing):
        try:
            out = os.path.join(self.user_data_dir, self.project.default_export_name())
            self.project.export(out, allow_missing=allow_missing,
                                line=getattr(self, "spacing_choice", "match"))
            text = f"Exported ({os.path.getsize(out) // 1024} KB)."
            if SharedStorage:
                self.shared_output = SharedStorage().copy_to_shared(out)
                text += (f" Saved as {self.project.default_export_name()}. "
                         "Tap Share / open, then open it in OnlyOffice.")
                self.share_btn.disabled = False
                self.share_btn.redraw()
            self.export_status.text = text
        except Exception:
            self.export_status.text = "Export failed: " + traceback.format_exc(limit=1)[-160:]

    def share(self, *args):
        try:
            self.sharer.share_file(self.shared_output)
        except Exception:
            self.export_status.text = "Could not open the share sheet."


if __name__ == "__main__":
    LabApp().run()
