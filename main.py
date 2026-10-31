import json
import math
import os
import threading

from kivy.app import App
from kivy.clock import Clock
from kivy.core.clipboard import Clipboard
from kivy.core.text import LabelBase
from kivy.core.window import Window
from kivy.graphics import Color, Line, RoundedRectangle
from kivy.metrics import dp, sp
from kivy.uix.behaviors import ButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.modalview import ModalView
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput
from kivy.uix.widget import Widget
from kivy.utils import escape_markup, get_color_from_hex

from zotero_engine import (
    get_authors,
    get_year,
    multiple_in_text,
    narrative_text,
    references_text,
    search_zotero,
)

APP_TITLE = "Blaykie"
APP_TAGLINE = "Cite smarter. APA 7 in seconds."

HERE = os.path.dirname(os.path.abspath(__file__))
LOGO_FILE = os.path.join(HERE, "icon.png")
FONT_REGULAR = os.path.join(HERE, "Poppins-Regular.ttf")
FONT_BOLD = os.path.join(HERE, "Poppins-Bold.ttf")

BOTTOM_GAP = 36   # empty space (dp) under the buttons, away from the navigation bar


def col(hex_value, alpha=1.0):
    r, g, b, _ = get_color_from_hex(hex_value)
    return (r, g, b, alpha)


BG = "#0E1016"
SURFACE = "#171A23"
SURFACE2 = "#222744"
SURFACE2_DOWN = "#2D3358"
LINE = "#2C3244"
BORDER2 = "#4A5168"
ACCENT = "#5B4BEA"
ACCENT_DOWN = "#4A3BD0"
ACCENT_TEXT = "#B9B0FF"
TEXT = "#F3F4F8"
MUTED = "#A3A9BC"
ERROR = "#FF8A8A"

Window.clearcolor = col(BG)
# Keeps the text boxes visible when the phone keyboard opens
Window.softinput_mode = "below_target"

# Use Poppins everywhere when the font files are present
try:
    if os.path.exists(FONT_REGULAR) and os.path.exists(FONT_BOLD):
        LabelBase.register(
            name="Roboto", fn_regular=FONT_REGULAR, fn_bold=FONT_BOLD
        )
except Exception:
    pass


def paint(widget, fill_hex, radius, edge_hex=None):
    """Give a layout a rounded coloured background."""
    with widget.canvas.before:
        Color(*col(fill_hex))
        fill = RoundedRectangle(radius=radius)
        edge = None
        if edge_hex:
            Color(*col(edge_hex))
            edge = Line(width=1)

    def update(*args):
        fill.pos = widget.pos
        fill.size = widget.size
        if edge is not None:
            edge.rounded_rectangle = (
                widget.x, widget.y, widget.width, widget.height, radius[0]
            )

    widget.bind(pos=update, size=update)
    update()


def small_label(text, color=MUTED, size=12, bold=False, height=20):
    label = Label(
        text=text,
        color=col(color),
        font_size=sp(size),
        bold=bold,
        halign="left",
        valign="middle",
        size_hint_y=None,
        height=dp(height),
    )
    label.bind(size=lambda obj, value: setattr(obj, "text_size", value))
    return label


# ---------------------------------------------------------------------
#  Custom widgets
# ---------------------------------------------------------------------

class Logo(Widget):
    """The cat logo with rounded corners."""

    def __init__(self, source, **kwargs):
        super().__init__(**kwargs)
        with self.canvas:
            Color(1, 1, 1, 1)
            self.shape = RoundedRectangle(
                source=source, pos=self.pos, size=self.size, radius=[dp(14)]
            )
        self.bind(pos=self.update_shape, size=self.update_shape)

    def update_shape(self, *args):
        self.shape.pos = self.pos
        self.shape.size = self.size


class PillButton(ButtonBehavior, Label):
    """Rounded button. style: primary, secondary or ghost."""

    def __init__(self, style="secondary", **kwargs):
        kwargs.setdefault("font_size", sp(14))
        kwargs.setdefault("bold", True)
        super().__init__(**kwargs)
        self.style = style
        with self.canvas.before:
            self.fill_color = Color(0, 0, 0, 0)
            self.fill = RoundedRectangle(radius=[dp(16)])
            self.edge_color = Color(0, 0, 0, 0)
            self.edge = Line(width=1)
        self.bind(
            pos=self.redraw, size=self.redraw,
            state=self.redraw, disabled=self.redraw,
        )
        self.redraw()

    def redraw(self, *args):
        pressed = self.state == "down"
        if self.style == "primary":
            fill = col(ACCENT_DOWN if pressed else ACCENT)
            edge = fill
            text = (1, 1, 1, 1)
        elif self.style == "secondary":
            fill = col(SURFACE2_DOWN if pressed else SURFACE2)
            edge = col("#3B4170")
            text = col(ACCENT_TEXT)
        else:
            fill = col(LINE, 0.7 if pressed else 0.0)
            edge = col(LINE)
            text = col(MUTED)

        fade = 0.5 if self.disabled else 1.0
        self.fill_color.rgba = (fill[0], fill[1], fill[2], fill[3] * fade)
        self.edge_color.rgba = (edge[0], edge[1], edge[2], edge[3] * fade)
        self.color = (text[0], text[1], text[2], text[3] * fade)
        self.fill.pos = self.pos
        self.fill.size = self.size
        self.edge.rounded_rectangle = (
            self.x, self.y, self.width, self.height, dp(16)
        )


class InputBox(BoxLayout):
    """A rounded text field. The real TextInput is available as .input"""

    def __init__(self, hint="", password=False, **kwargs):
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", dp(52))
        super().__init__(padding=[dp(16), 0, dp(16), 0], **kwargs)
        with self.canvas.before:
            Color(*col(SURFACE))
            self.fill = RoundedRectangle(radius=[dp(16)])
            self.edge_color = Color(*col(LINE))
            self.edge = Line(width=1)

        self.input = TextInput(
            multiline=False,
            password=password,
            hint_text=hint,
            background_normal="",
            background_active="",
            background_color=(0, 0, 0, 0),
            foreground_color=col(TEXT),
            hint_text_color=col(MUTED),
            cursor_color=col(ACCENT_TEXT),
            selection_color=col(ACCENT, 0.4),
            font_size=sp(15),
            padding=[0, dp(15), 0, dp(10)],
            write_tab=False,
        )
        self.add_widget(self.input)
        self.bind(pos=self.redraw, size=self.redraw)
        self.input.bind(focus=self.on_input_focus)
        self.redraw()

    def on_input_focus(self, instance, focused):
        self.edge_color.rgba = col(ACCENT if focused else LINE)

    def redraw(self, *args):
        self.fill.pos = self.pos
        self.fill.size = self.size
        self.edge.rounded_rectangle = (
            self.x, self.y, self.width, self.height, dp(16)
        )


class GearButton(ButtonBehavior, Widget):
    """Settings button drawn with simple lines."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.bind(pos=self.redraw, size=self.redraw, state=self.redraw)
        self.redraw()

    def redraw(self, *args):
        self.canvas.clear()
        cx, cy = self.center_x, self.center_y
        radius = dp(6.5)
        with self.canvas:
            Color(*col(SURFACE2 if self.state == "down" else SURFACE))
            RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(14)])
            Color(*col(LINE))
            Line(
                rounded_rectangle=(
                    self.x, self.y, self.width, self.height, dp(14)
                ),
                width=1,
            )
            Color(*col(MUTED))
            Line(circle=(cx, cy, radius), width=dp(1.6))
            Line(circle=(cx, cy, dp(2.2)), width=dp(1.4))
            for step in range(8):
                angle = math.radians(step * 45)
                inner = radius + dp(1)
                outer = radius + dp(4)
                Line(
                    points=[
                        cx + inner * math.cos(angle),
                        cy + inner * math.sin(angle),
                        cx + outer * math.cos(angle),
                        cy + outer * math.sin(angle),
                    ],
                    width=dp(2),
                )


class CheckMark(Widget):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.checked = False
        self.bind(pos=self.redraw, size=self.redraw)
        self.redraw()

    def set_checked(self, value):
        self.checked = value
        self.redraw()

    def redraw(self, *args):
        self.canvas.clear()
        x, y, w, h = self.x, self.y, self.width, self.height
        with self.canvas:
            if self.checked:
                Color(*col(ACCENT))
                RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(8)])
                Color(1, 1, 1, 1)
                Line(
                    points=[
                        x + 0.25 * w, y + 0.52 * h,
                        x + 0.43 * w, y + 0.30 * h,
                        x + 0.76 * w, y + 0.72 * h,
                    ],
                    width=dp(2),
                )
            else:
                Color(*col(BORDER2))
                Line(rounded_rectangle=(x, y, w, h, dp(8)), width=dp(1.6))


class ResultCard(ButtonBehavior, BoxLayout):
    """One search result. Tap anywhere on the card to select it."""

    def __init__(self, text, toggle_callback, **kwargs):
        super().__init__(
            orientation="horizontal",
            size_hint_y=None,
            height=dp(72),
            padding=dp(14),
            spacing=dp(12),
            **kwargs
        )
        self.toggle_callback = toggle_callback
        self.selected = False

        with self.canvas.before:
            self.fill_color = Color(*col(SURFACE))
            self.fill = RoundedRectangle(radius=[dp(18)])
            self.edge_color = Color(*col(LINE))
            self.edge = Line(width=1)

        self.mark = CheckMark(
            size_hint=(None, None), size=(dp(24), dp(24)),
            pos_hint={"top": 1},
        )
        self.label = Label(
            text=text,
            markup=True,
            halign="left",
            valign="top",
            size_hint_y=None,
            height=dp(40),
            color=col(MUTED),
            font_size=sp(13),
            pos_hint={"top": 1},
        )
        self.label.bind(width=self.fit_width, texture_size=self.fit_height)
        self.add_widget(self.mark)
        self.add_widget(self.label)

        self.bind(pos=self.redraw, size=self.redraw)
        self.redraw()

    def fit_width(self, label, width):
        label.text_size = (width, None)

    def fit_height(self, label, size):
        label.height = size[1]
        self.height = max(dp(64), size[1] + dp(28))

    def redraw(self, *args):
        self.fill.pos = self.pos
        self.fill.size = self.size
        self.edge.rounded_rectangle = (
            self.x, self.y, self.width, self.height, dp(18)
        )

    def set_selected(self, value, notify=True):
        self.selected = value
        if value:
            self.fill_color.rgba = col(ACCENT, 0.14)
            self.edge_color.rgba = col(ACCENT)
        else:
            self.fill_color.rgba = col(SURFACE)
            self.edge_color.rgba = col(LINE)
        self.mark.set_checked(value)
        if notify:
            self.toggle_callback(value)

    def on_release(self):
        self.set_selected(not self.selected)


# ---------------------------------------------------------------------
#  The app
# ---------------------------------------------------------------------

class ZoteroApp(App):

    def build(self):
        self.results = []
        self.selected = []
        self.config_path = os.path.join(self.user_data_dir, "credentials.json")

        root = BoxLayout(orientation="vertical")

        content = BoxLayout(
            orientation="vertical",
            padding=[dp(20), dp(20), dp(20), 0],
            spacing=dp(10),
        )

        # ----- header: logo, name, settings -----
        header = BoxLayout(
            orientation="horizontal", size_hint_y=None, height=dp(56),
            spacing=dp(12),
        )
        if os.path.exists(LOGO_FILE):
            header.add_widget(Logo(
                LOGO_FILE, size_hint=(None, None), size=(dp(46), dp(46)),
                pos_hint={"center_y": 0.5},
            ))
        title = Label(
            text=(
                f"[size=22sp][b]{APP_TITLE}[/b][/size]\n"
                f"[size=12sp][color=#A3A9BC]{APP_TAGLINE}[/color][/size]"
            ),
            markup=True,
            halign="left",
            valign="middle",
            color=col(TEXT),
        )
        title.bind(size=lambda obj, value: setattr(obj, "text_size", value))
        header.add_widget(title)

        gear = GearButton(
            size_hint=(None, None), size=(dp(44), dp(44)),
            pos_hint={"center_y": 0.5},
        )
        gear.bind(on_release=self.open_settings)
        header.add_widget(gear)
        content.add_widget(header)

        # ----- search -----
        search_row = BoxLayout(
            size_hint_y=None, height=dp(52), spacing=dp(10)
        )
        self.search_box = InputBox(hint="Search Zotero...")
        self.search_input = self.search_box.input
        self.search_input.bind(on_text_validate=self.do_search)
        search_row.add_widget(self.search_box)

        self.search_button = PillButton(
            text="Search", style="primary",
            size_hint=(None, None), size=(dp(100), dp(52)),
        )
        self.search_button.bind(on_release=self.do_search)
        search_row.add_widget(self.search_button)
        content.add_widget(search_row)

        # ----- status line -----
        status_row = BoxLayout(
            size_hint_y=None, height=dp(34), padding=[dp(2), 0, dp(2), 0]
        )
        self.status = small_label(
            "Open settings (gear) to add your Zotero details, then search.",
            size=13, bold=True, height=34,
        )
        self.count_label = small_label("", color=ACCENT_TEXT, size=12,
                                       bold=True, height=34)
        self.count_label.halign = "right"
        self.count_label.size_hint_x = None
        self.count_label.width = dp(100)
        status_row.add_widget(self.status)
        status_row.add_widget(self.count_label)
        content.add_widget(status_row)

        # ----- results -----
        scroll = ScrollView(bar_width=dp(3))
        self.results_box = GridLayout(
            cols=1, spacing=dp(10), size_hint_y=None,
            padding=[0, 0, 0, dp(12)],
        )
        self.results_box.bind(
            minimum_height=self.results_box.setter("height")
        )
        scroll.add_widget(self.results_box)
        content.add_widget(scroll)
        root.add_widget(content)

        # ----- action buttons (kept clear of the navigation bar) -----
        grid_height = dp(52) * 2 + dp(10)
        bar = BoxLayout(
            orientation="vertical", size_hint_y=None,
            padding=[dp(20), dp(14), dp(20), dp(BOTTOM_GAP)],
            height=dp(14) + grid_height + dp(BOTTOM_GAP),
        )
        paint(bar, SURFACE, [dp(24), dp(24), 0, 0])

        grid = GridLayout(
            cols=2, spacing=dp(10), size_hint_y=None, height=grid_height,
            row_default_height=dp(52), row_force_default=True,
        )
        for text, style, handler in (
            ("In-text citation", "primary", self.copy_citation),
            ("Full APA 7", "secondary", self.copy_reference),
            ("Narrative", "secondary", self.copy_narrative),
            ("Clear", "ghost", self.clear_results),
        ):
            button = PillButton(text=text, style=style)
            button.bind(on_release=handler)
            grid.add_widget(button)
        bar.add_widget(grid)
        root.add_widget(bar)

        self.build_settings()
        self.load_credentials()
        return root

    def on_start(self):
        if not self.user_id.text.strip() or not self.api_key.text.strip():
            Clock.schedule_once(self.open_settings, 0.6)
        else:
            self.status.text = "Type something to search your library."

    # ---------- keep the app alive when switching apps ----------

    def on_pause(self):
        return True

    def on_resume(self):
        pass

    # ---------- settings pop-up ----------

    def build_settings(self):
        panel = BoxLayout(
            orientation="vertical", padding=dp(20), spacing=dp(8)
        )
        paint(panel, SURFACE, [dp(24)] * 4, edge_hex=LINE)

        panel.add_widget(small_label(
            "Zotero settings", color=TEXT, size=18, bold=True, height=32
        ))

        hint = Label(
            text="Find your User ID and API key at zotero.org/settings/keys "
                 "(give the key read access).",
            color=col(MUTED),
            font_size=sp(12),
            halign="left",
            valign="top",
            size_hint_y=None,
            height=dp(48),
        )
        hint.bind(size=lambda obj, value: setattr(obj, "text_size", value))
        panel.add_widget(hint)

        panel.add_widget(small_label("User ID", bold=True))
        id_box = InputBox(hint="Numbers only, e.g. 1234567")
        self.user_id = id_box.input
        panel.add_widget(id_box)

        panel.add_widget(small_label("API Key", bold=True))
        key_box = InputBox(hint="Paste your API key", password=True)
        self.api_key = key_box.input
        panel.add_widget(key_box)

        self.settings_message = small_label("", color=ERROR, size=12, height=22)
        panel.add_widget(self.settings_message)

        buttons = BoxLayout(
            size_hint_y=None, height=dp(52), spacing=dp(10)
        )
        cancel = PillButton(text="Cancel", style="ghost")
        cancel.bind(on_release=lambda *a: self.settings_view.dismiss())
        save = PillButton(text="Save", style="primary")
        save.bind(on_release=self.save_settings)
        buttons.add_widget(cancel)
        buttons.add_widget(save)
        panel.add_widget(buttons)

        self.settings_view = ModalView(
            size_hint=(0.92, None),
            height=dp(440),
            auto_dismiss=True,
            background="",
            background_color=(0, 0, 0, 0),
            overlay_color=(0, 0, 0, 0.7),
        )
        self.settings_view.add_widget(panel)

    def open_settings(self, *args):
        self.settings_message.text = ""
        self.settings_view.open()

    def save_settings(self, *args):
        user_id = self.user_id.text.strip()
        api_key = self.api_key.text.strip()

        if not user_id.isdigit():
            self.settings_message.text = (
                "User ID must be numbers only (not your username)."
            )
            return
        if not api_key:
            self.settings_message.text = "Please enter your API key."
            return

        self.save_credentials(user_id, api_key)
        self.settings_view.dismiss()
        self.status.text = "Settings saved. Type something to search."

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
            self.status.text = "Add your Zotero User ID and API key first."
            self.open_settings()
            return
        if not user_id.isdigit():
            self.status.text = "User ID must be numbers only (not your username)."
            self.open_settings()
            return
        if not query:
            self.status.text = "Please enter a search term."
            return

        self.status.text = "Searching Zotero..."
        self.count_label.text = ""
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
        self.status.text = f"{len(results)} result(s)"

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

            text = (
                f"[size=14sp][color=#F3F4F8][b]"
                f"{escape_markup(author_text)} \u00b7 {escape_markup(year)}"
                f"[/b][/color][/size]\n"
                f"{escape_markup(title)}"
            )
            card = ResultCard(
                text,
                lambda selected, n=number: self.select_result(n, selected),
            )
            self.results_box.add_widget(card)

    # ---------- selection and copying ----------

    def select_result(self, number, active):
        if active:
            if number not in self.selected:
                self.selected.append(number)
        else:
            if number in self.selected:
                self.selected.remove(number)
        self.selected.sort()
        count = len(self.selected)
        self.count_label.text = f"{count} selected" if count else ""

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
        self.status.text = "In-text citation copied."

    def copy_reference(self, instance):
        items = self.selected_items()
        if not items:
            self.status.text = "Select at least one reference."
            return
        Clipboard.copy(references_text(items))
        self.status.text = "APA 7 reference(s) copied."

    def copy_narrative(self, instance):
        items = self.selected_items()
        if not items:
            self.status.text = "Select at least one reference."
            return
        Clipboard.copy(narrative_text(items))
        self.status.text = "Narrative citation copied."

    def clear_results(self, instance):
        self.results = []
        self.selected = []
        self.results_box.clear_widgets()
        self.count_label.text = ""
        self.status.text = "Cleared."


if __name__ == "__main__":
    ZoteroApp().run()
