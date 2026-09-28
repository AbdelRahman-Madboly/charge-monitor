import sys
from pathlib import Path

import pytest

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


def test_vector_stays_inside_safe_zone():
    rows = generate.parse_icon(generate.read_lines(generate.ICON_PATH))
    xml = generate.render_vector(rows, generate.icon_runs(rows), generate.ICON_COLORS.__getitem__)
    margin = (generate.ICON_VIEWPORT - generate.ICON_SAFE) / 2.0
    for token in xml.split('pathData="M')[1:]:
        x, y = (float(v) for v in token.split(" ")[0].split(","))
        assert x >= margin - 0.01 and y >= margin - 0.01


def test_shipped_icon_parses_and_uses_known_colours():
    rows = generate.parse_icon(generate.read_lines(generate.ICON_PATH))
    assert len(rows) == 26 and len(rows[0]) == 20
    used = {ch for row in rows for ch in row} - {"."}
    assert used <= set(generate.ICON_COLORS)


def test_generated_files_carry_notice():
    for path in (generate.FOREGROUND_OUT, generate.MONOCHROME_OUT):
        text = path.read_text(encoding="utf-8")
        assert "Do not edit by hand" in text
        assert "--" not in text.split("<!--", 1)[1].split("-->", 1)[0]


def test_monochrome_is_single_colour():
    text = generate.MONOCHROME_OUT.read_text(encoding="utf-8")
    fills = {part.split('"')[0] for part in text.split('fillColor="')[1:]}
    assert fills == {generate.MONOCHROME_FILL}


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
