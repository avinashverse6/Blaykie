"""Shared look for Blaykie: colours and small widgets."""

import math

from kivy.graphics import Color, Line, RoundedRectangle
from kivy.metrics import dp, sp
from kivy.uix.behaviors import ButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.widget import Widget
from kivy.utils import get_color_from_hex

from theme import resolve_color


def col(hex_value, alpha=1.0):
    r, g, b, _ = get_color_from_hex(resolve_color(hex_value))
    return (r, g, b, alpha)


# Semantic roles resolve against the currently active palette at draw time.
BG = "BG"
SURFACE = "SURFACE"
SURFACE2 = "SURFACE2"
SURFACE2_DOWN = "SURFACE2_DOWN"
LINE = "LINE"
BORDER2 = "BORDER2"
ACCENT = "ACCENT"
ACCENT_DOWN = "ACCENT_DOWN"
ACCENT_TEXT = "ACCENT_TEXT"
TEXT = "TEXT"
MUTED = "MUTED"
ERROR = "ERROR"




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
            edge = col("BUTTON_BORDER")
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


