import unittest

from theme import (
    DARK_PALETTE,
    LIGHT_PALETTE,
    THEME_MODES,
    active_palette,
    configure_theme,
    is_dark,
    resolve_color,
)


def luminance(hex_color):
    channels = [int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
              for v in channels]
    return sum(c * w for c, w in zip(linear, (0.2126, 0.7152, 0.0722)))


def contrast(first, second):
    high, low = sorted((luminance(first), luminance(second)), reverse=True)
    return (high + 0.05) / (low + 0.05)


class ThemeTests(unittest.TestCase):
    def tearDown(self):
        configure_theme("system", system_dark=False)

    def test_supported_modes(self):
        self.assertEqual(THEME_MODES, ("light", "dark", "system"))
        for mode in THEME_MODES:
            configure_theme(mode)

    def test_explicit_modes_ignore_system_setting(self):
        configure_theme("light", system_dark=True)
        self.assertFalse(is_dark())
        self.assertIs(active_palette(), LIGHT_PALETTE)
        configure_theme("dark", system_dark=False)
        self.assertTrue(is_dark())
        self.assertIs(active_palette(), DARK_PALETTE)

    def test_system_mode_tracks_device_setting(self):
        configure_theme("system", system_dark=False)
        self.assertFalse(is_dark())
        self.assertEqual(resolve_color("BG"), LIGHT_PALETTE["BG"])
        configure_theme("system", system_dark=True)
        self.assertTrue(is_dark())
        self.assertEqual(resolve_color("BG"), DARK_PALETTE["BG"])

    def test_invalid_mode_is_rejected(self):
        with self.assertRaises(ValueError):
            configure_theme("sepia")

    def test_body_and_muted_text_have_readable_contrast(self):
        for palette in (LIGHT_PALETTE, DARK_PALETTE):
            for text_role in ("TEXT", "MUTED"):
                self.assertGreaterEqual(
                    contrast(palette[text_role], palette["SURFACE"]), 4.5,
                    f"{text_role} lacks contrast in palette {palette}",
                )


if __name__ == "__main__":
    unittest.main()
