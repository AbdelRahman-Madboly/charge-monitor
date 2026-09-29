import math
import sys
from pathlib import Path

import pytest
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate  # noqa: E402


def test_parse_icon_skips_header_and_reads_rows():
    lines = ["# comment", "KK..", "@icon", "# inline comment", ".KK.", "KYYK", ""]
    rows = generate.parse_icon(lines)
    assert rows == [".KK.", "KYYK"]


def test_parse_icon_rejects_ragged_rows():
    with pytest.raises(ValueError):
        generate.parse_icon(["@icon", "KKK", "KK"])


def test_parse_icon_rejects_empty():
    with pytest.raises(ValueError):
        generate.parse_icon(["@icon", "# nothing here"])


def test_runs_merge_same_colour_and_skip_transparent():
    runs = generate.icon_runs(["KKK.YY"])
    assert runs == [(0, 0, 3, "K"), (4, 0, 2, "Y")]


def test_runs_filter_by_keys():
    runs = generate.icon_runs(["KggY"], keys={"K", "Y"})
    assert runs == [(0, 0, 1, "K"), (3, 0, 1, "Y")]


def test_vector_stays_inside_safe_circle():
    rows = generate.parse_icon(generate.read_lines(generate.ICON_PATH))
    xml = generate.render_vector(rows, generate.icon_runs(rows), generate.ICON_COLORS.__getitem__)
    centre = generate.ICON_VIEWPORT / 2.0
    radius = generate.ICON_SAFE_DIAMETER / 2.0
    for token in xml.split('pathData="M')[1:]:
        x, y = (float(v) for v in token.split(" ")[0].split(","))
        assert math.hypot(x - centre, y - centre) <= radius + 0.01


def test_shipped_icon_parses_and_uses_known_colours():
    rows = generate.parse_icon(generate.read_lines(generate.ICON_PATH))
    assert len(rows) == 27 and len(rows[0]) == 16
    used = {ch for row in rows for ch in row} - {"."}
    assert used <= set(generate.ICON_COLORS)


def test_foreground_art_stays_inside_safe_circle():
    source = Image.open(generate.SOURCE_PNG).convert("RGBA")
    edge = 216
    fg = generate.render_foreground(source, edge)
    assert fg.size == (edge, edge)
    px = fg.load()
    centre = edge / 2.0
    radius = edge * generate.ICON_SAFE_DIAMETER / generate.ICON_VIEWPORT / 2.0
    for y in range(edge):
        for x in range(edge):
            if sum(abs(c - t) for c, t in zip(px[x, y], generate.TILE_COLOR)) > generate.ART_THRESHOLD:
                assert math.hypot(x + 0.5 - centre, y + 0.5 - centre) <= radius, (x, y)


def test_background_colour_matches_source_tile():
    source = Image.open(generate.SOURCE_PNG).convert("RGB")
    assert source.getpixel((128, generate.TILE_RIM_PX + 2)) == generate.TILE_COLOR
    expected = "#%02X%02X%02X" % generate.TILE_COLOR
    colors = generate.COLORS_XML.read_text(encoding="utf-8")
    assert f'<color name="ic_launcher_background">{expected}</color>' in colors


def test_shipped_foregrounds_exist_at_every_density():
    for density, edge in generate.DENSITIES.items():
        path = generate.RES_DIR / f"mipmap-{density}" / "ic_launcher_foreground.png"
        assert Image.open(path).size == (edge, edge)


def test_generated_monochrome_carries_notice():
    text = generate.MONOCHROME_OUT.read_text(encoding="utf-8")
    assert "Do not edit by hand" in text
    assert "--" not in text.split("<!--", 1)[1].split("-->", 1)[0]


def test_monochrome_is_single_colour():
    text = generate.MONOCHROME_OUT.read_text(encoding="utf-8")
    fills = {part.split('"')[0] for part in text.split('fillColor="')[1:]}
    assert fills == {generate.MONOCHROME_FILL}


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
