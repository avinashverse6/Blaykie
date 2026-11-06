"""Theme modes and semantic color palettes for Blaykie.

This module deliberately has no Kivy dependency so the palette and mode
selection rules can be tested on any Python host.
"""

LIGHT_PALETTE = {
    "BG": "#F5F6FA",
    "SURFACE": "#FFFFFF",
    "SURFACE2": "#ECEFFD",
    "SURFACE2_DOWN": "#DCE1FA",
    "LINE": "#D9DDE8",
    "BORDER2": "#8B91A3",
    "BUTTON_BORDER": "#C5C9DD",
    "ACCENT": "#5142D8",
    "ACCENT_DOWN": "#4032B8",
    "ACCENT_TEXT": "#4032B8",
    "TEXT": "#191B25",
    "MUTED": "#555B6D",
    "ERROR": "#B42332",
}

DARK_PALETTE = {
    "BG": "#0E1016",
    "SURFACE": "#171A23",
    "SURFACE2": "#222744",
    "SURFACE2_DOWN": "#2D3358",
    "LINE": "#2C3244",
    "BORDER2": "#4A5168",
    "BUTTON_BORDER": "#3B4170",
    "ACCENT": "#5B4BEA",
    "ACCENT_DOWN": "#4A3BD0",
    "ACCENT_TEXT": "#B9B0FF",
    "TEXT": "#F3F4F8",
    "MUTED": "#A3A9BC",
    "ERROR": "#FF8A8A",
}

THEME_MODES = ("light", "dark", "system")
_active_mode = "dark"
_system_dark = True


def configure_theme(mode, system_dark=False):
    """Set requested mode and OS appearance; return the effective palette."""
    global _active_mode, _system_dark
    if mode not in THEME_MODES:
        raise ValueError("mode must be light, dark, or system")
    _active_mode = mode
    _system_dark = bool(system_dark)
    return active_palette()


def active_mode():
    return _active_mode


def is_dark():
    return _active_mode == "dark" or (_active_mode == "system" and _system_dark)


def active_palette():
    return DARK_PALETTE if is_dark() else LIGHT_PALETTE


def resolve_color(value):
    """Resolve a semantic role to a hex color; pass literal colors through."""
    return active_palette().get(value, value)
