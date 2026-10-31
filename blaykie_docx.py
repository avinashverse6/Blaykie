"""Blaykie DOCX core.

Opens a .docx, shows its text, lets you put a citation (or delete text) at an
exact position, builds an APA 7 reference list and exports a NEW .docx.

Only plain python (zipfile + re). The original file is never changed: every
edit is kept as a list of operations and applied to a copy of word/document.xml.
Everything outside the edited spots stays byte for byte the same.
"""

import re
import zipfile
from collections import Counter

from zotero_engine import (
    apa_reference_parts,
    multiple_in_text,
    narrative_text,
    page_label,
    sorted_items,
    unique_items,
    year_suffixes,
)

BOOKMARK_PREFIX = "BLK_"
BIB_BOOKMARK = "BLK_BIBLIOGRAPHY"

TAG_RE = re.compile(
    r'<(/?)([A-Za-z_][\w:.\-]*)((?:[^>"\']|"[^"]*"|\'[^\']*\')*?)(/?)>'
)
ENTITY_RE = re.compile(r"&(#x[0-9A-Fa-f]+|#\d+|amp|lt|gt|quot|apos);")
OBJECT_CHAR = "\ufffc"


class NotEditable(Exception):
    """The chosen spot is inside something Blaykie must not touch."""


# ---------------------------------------------------------------------
#  tiny XML helpers (string based, so nothing else in the file changes)
# ---------------------------------------------------------------------

def unescape(text):
    def fix(match):
        name = match.group(1)
        if name[0] == "#":
            if name[1] in "xX":
                return chr(int(name[2:], 16))
            return chr(int(name[1:]))
        return {"amp": "&", "lt": "<", "gt": ">", "quot": '"', "apos": "'"}[name]
    return ENTITY_RE.sub(fix, text)


def escape(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def children(xml, lo, hi):
    """Direct child elements inside xml[lo:hi] -> [(tag, start, end)]."""
    found = []
    depth = 0
    start = None
    first_tag = None
    for match in TAG_RE.finditer(xml, lo, hi):
        closing, tag, _attrs, self_closing = match.groups()
        if closing:
            depth -= 1
            if depth == 0:
                found.append((first_tag, start, match.end()))
        elif self_closing:
            if depth == 0:
                found.append((tag, match.start(), match.end()))
        else:
            if depth == 0:
                start = match.start()
                first_tag = tag
            depth += 1
    return found


def inner_span(xml, start, end, closing_tag):
    """(lo, hi) of the inside of the element xml[start:end]."""
    open_match = TAG_RE.match(xml, start)
    if open_match.group(4):               # self closing, nothing inside
        return open_match.end(), open_match.end()
    return open_match.end(), end - len(closing_tag)


def text_pieces(xml, lo, hi):
    """Visible text of a run-level fragment (used for locked content)."""
    out = []
    for tag, s, e in children(xml, lo, hi):
        if tag == "w:t":
            a, b = inner_span(xml, s, e, "</w:t>")
            out.append(unescape(xml[a:b]))
        elif tag == "w:tab":
            out.append("\t")
        elif tag in ("w:br", "w:cr"):
            out.append("\n")
        elif tag == "w:noBreakHyphen":
            out.append("-")
        elif tag in ("w:sym", "w:drawing", "w:pict", "w:object",
                     "w:footnoteReference", "w:endnoteReference",
                     "w:commentReference", "mc:AlternateContent"):
            out.append(OBJECT_CHAR)
    return "".join(out)


def container_text(xml, start, end):
    """Text inside a wrapper such as w:hyperlink / w:ins / w:sdt."""
    out = []
    for match in re.finditer(r"<w:t(?:\s[^>]*)?>(.*?)</w:t>", xml[start:end], re.S):
        out.append(unescape(match.group(1)))
    return "".join(out)


# ---------------------------------------------------------------------
#  paragraph model
# ---------------------------------------------------------------------

class Seg:
    __slots__ = ("kind", "tag", "start", "end", "text", "rpr",
                 "off0", "off1", "depth")

    def __init__(self, kind, tag, start, end):
        self.kind = kind          # text (editable run) | locked | meta
        self.tag = tag
        self.start = start
        self.end = end
        self.text = ""
        self.rpr = ""
        self.off0 = self.off1 = 0
        self.depth = 0            # field depth before this segment


CLOSING_METAS = ("w:bookmarkEnd", "w:proofErr", "w:commentRangeEnd")
META_TAGS = ("w:pPr", "w:bookmarkStart", "w:bookmarkEnd", "w:proofErr",
             "w:commentRangeStart", "w:commentRangeEnd", "w:permStart",
             "w:permEnd", "w:del", "w:moveFrom", "w:moveFromRangeStart",
             "w:moveFromRangeEnd", "w:moveToRangeStart", "w:moveToRangeEnd",
             "w:sectPr")


class Para:
    def __init__(self, xml):
        self.xml = xml
        self.segs = []
        self.text = ""
        self.final_depth = 0
        self.inner_end = len(xml)
        self._parse()

    def _parse(self):
        xml = self.xml
        open_match = TAG_RE.match(xml, 0)
        if open_match.group(4):
            self.inner_end = len(xml)
            return
        lo = open_match.end()
        hi = xml.rfind("</w:p>")
        self.inner_end = hi

        depth = 0
        offset = 0
        for tag, s, e in children(xml, lo, hi):
            seg = Seg("meta", tag, s, e)
            seg.depth = depth
            seg.off0 = offset

            if tag == "w:r":
                delta = self._read_run(seg, depth)
                depth += delta
            elif tag in META_TAGS:
                pass
            else:                           # hyperlink, ins, sdt, fldSimple ...
                seg.kind = "locked"
                seg.text = container_text(xml, s, e)

            if depth > 0 and seg.kind == "text":
                seg.kind = "locked"
            offset += len(seg.text)
            seg.off1 = offset
            self.segs.append(seg)

        self.final_depth = depth
        self.text = "".join(seg.text for seg in self.segs)

    def _read_run(self, seg, depth):
        xml = self.xml
        head = TAG_RE.match(xml, seg.start)
        if head.group(4):
            return 0
        lo, hi = inner_span(xml, seg.start, seg.end, "</w:r>")
        kids = children(xml, lo, hi)
        delta = 0
        simple = True
        for tag, s, e in kids:
            if tag == "w:rPr":
                seg.rpr = xml[s:e]
            elif tag == "w:fldChar":
                kind = re.search(r'w:fldCharType="(\w+)"', xml[s:e])
                if kind and kind.group(1) == "begin":
                    delta += 1
                elif kind and kind.group(1) == "end":
                    delta -= 1
                simple = False
            elif tag not in ("w:t", "w:lastRenderedPageBreak"):
                simple = False

        if simple:
            seg.kind = "text"
            seg.text = "".join(
                unescape(xml[a:b])
                for tag, s, e in kids if tag == "w:t"
                for a, b in [inner_span(xml, s, e, "</w:t>")]
            )
        else:
            seg.kind = "locked"
            seg.text = text_pieces(xml, lo, hi)
        return delta

    # ----- positions -----

    def offset_before(self, index):
        if index < len(self.segs):
            return self.segs[index].off0
        return len(self.text)

    def depth_before(self, index):
        if index < len(self.segs):
            return self.segs[index].depth
        return self.final_depth

    def first_content_index(self):
        if self.segs and self.segs[0].tag == "w:pPr":
            return 1
        return 0

    def insertion_index(self, offset):
        """Index in segs where new runs for `offset` go (a boundary)."""
        total = len(self.text)
        if offset < 0 or offset > total:
            raise NotEditable("Position is outside the paragraph.")
        for index in range(self.first_content_index(), len(self.segs) + 1):
            if self.offset_before(index) != offset:
                continue
            if self.depth_before(index) != 0:
                raise NotEditable("This spot is inside a field (for example "
                                  "a Zotero citation). Blaykie will not touch it.")
            while (index < len(self.segs)
                   and self.segs[index].tag in CLOSING_METAS
                   and self.segs[index].off0 == offset):
                index += 1
            return index
        raise NotEditable("Position is inside a part Blaykie cannot edit.")

    def seg_at(self, offset):
        """Text segment that contains the character at `offset`."""
        for index, seg in enumerate(self.segs):
            if seg.kind in ("text", "locked") and seg.off0 <= offset < seg.off1:
                return index, seg
        return None, None

    def format_source(self, start, end):
        """rPr of the text just before the spot (else just after)."""
        for seg in self.segs:
            if seg.kind == "text" and seg.text and seg.off0 < start <= seg.off1:
                return seg.rpr
        for seg in self.segs:
            if seg.kind == "text" and seg.text and seg.off0 >= end:
                return seg.rpr
        for seg in self.segs:
            if seg.kind == "text":
                return seg.rpr
        return ""

    # ----- editing -----

    def replace(self, start, end, new_xml, rpr_override=None):
        """Replace [start,end) with `new_xml` (already built runs).
        Returns the new paragraph xml."""
        xml = self.xml
        if start == end:
            seg_index, seg = self.seg_at(start)
            if seg is not None and seg.off0 < start < seg.off1:
                if seg.kind != "text":
                    raise NotEditable("This spot is inside a link, field or "
                                      "special item. Blaykie will not touch it.")
                cut = start - seg.off0
                left = make_run(seg.rpr, seg.text[:cut])
                right = make_run(seg.rpr, seg.text[cut:])
                return xml[:seg.start] + left + new_xml + right + xml[seg.end:]
            index_after = self.insertion_index(start)
            if index_after < len(self.segs):
                pos = self.segs[index_after].start
            else:
                pos = self.inner_end
            return xml[:pos] + new_xml + xml[pos:]

        # replacing a range: every touched piece must be an editable text run
        touched = [
            (i, s) for i, s in enumerate(self.segs)
            if s.kind in ("text", "locked") and s.off1 > start and s.off0 < end and s.text
        ]
        if not touched:
            raise NotEditable("Nothing to replace here.")
        if any(s.kind != "text" for i, s in touched):
            raise NotEditable("The selection includes a field, link or special "
                              "item. Blaykie will not change it.")
        first_i, first = touched[0]
        last_i, last = touched[-1]
        for index in range(first_i, last_i + 1):
            if self.segs[index].depth != 0:
                raise NotEditable("The selection is inside a field.")

        left = ""
        if start > first.off0:
            left = make_run(first.rpr, first.text[:start - first.off0])
        right = ""
        if end < last.off1:
            right = make_run(last.rpr, last.text[end - last.off0:])

        kept_metas = "".join(
            xml[s.start:s.end]
            for s in self.segs[first_i + 1:last_i]
            if s.kind != "text"
        )
        return (xml[:first.start] + left + new_xml + right + kept_metas
                + xml[last.end:])


def bookmark_name(item, number):
    key = re.sub(r"[^A-Za-z0-9]", "", str(item.get("key") or "ITEM")).upper()[:12]
    return f"{BOOKMARK_PREFIX}{key or 'ITEM'}_{number}"


def make_run(rpr, text):
    if text == "":
        return ""
    return ('<w:r>' + rpr + '<w:t xml:space="preserve">'
            + escape(text) + '</w:t></w:r>')


# ---------------------------------------------------------------------
#  citation text
# ---------------------------------------------------------------------

def build_citation_text(items, style="parenthetical", page="", prefix="",
                        context=None):
    items = unique_items(items)
    if not items:
        return ""
    if style == "narrative":
        text = narrative_text(items, context)
        if page and len(items) == 1 and text.endswith(")"):
            text = text[:-1] + ", " + page_label(page) + ")"
        return text

    text = multiple_in_text(items, context)
    inner = text[1:-1]
    if page and len(items) == 1:
        inner += ", " + page_label(page)
    if prefix:
        inner = prefix.strip() + " " + inner
    return "(" + inner + ")"


def smart_spacing(before, after, text):
    """Leading/trailing space so the citation sits naturally in the sentence."""
    lead = ""
    trail = ""
    if before and not before.isspace() and before not in "([{\u201c\u2018\"'/\u2014":
        lead = " "
    if after and (after.isalnum() or after in "([{\u201c\u2018"):
        trail = " "
    return lead, trail


# ---------------------------------------------------------------------
#  the project
# ---------------------------------------------------------------------

class Block:
    def __init__(self, tag, gap, xml):
        self.tag = tag
        self.gap = gap
        self.xml = xml
        self._para = None

    @property
    def para(self):
        if self.tag != "w:p":
            return None
        if self._para is None:
            self._para = Para(self.xml)
        return self._para

    def set_xml(self, xml):
        self.xml = xml
        self._para = None


class DocxProject:
    def __init__(self, path):
        self.path = path
        with zipfile.ZipFile(path) as archive:
            self.names = archive.namelist()
            self.raw = raw = archive.read("word/document.xml")
        self.bom = raw.startswith(b"\xef\xbb\xbf")
        self.original_xml = raw.decode("utf-8-sig")
        self.ops = []
        self._split_body()

        ids = [int(i) for i in re.findall(
            r'<w:bookmarkStart\b[^>]*\bw:id="(\d+)"', self.original_xml)]
        self.next_bookmark_id = (max(ids) + 1) if ids else 1
        numbers = [int(n) for n in re.findall(
            r'w:name="' + BOOKMARK_PREFIX + r'[A-Z0-9]+_(\d+)"', self.original_xml)]
        self.next_citation = (max(numbers) + 1) if numbers else 1
        self.has_tracked_changes = bool(
            re.search(r"<w:(ins|del)\b", self.original_xml))
        self._load_blocks()

    # ----- loading -----

    def _split_body(self):
        xml = self.original_xml
        body = re.search(r"<w:body\b[^>]*>", xml)
        if not body:
            raise ValueError("This file has no document body.")
        self.body_lo = body.end()
        self.body_hi = xml.rfind("</w:body>")
        self.prefix = xml[:self.body_lo]
        self.suffix = xml[self.body_hi:]

    def _load_blocks(self):
        xml = self.original_xml
        self.blocks = []
        cursor = self.body_lo
        for tag, s, e in children(xml, self.body_lo, self.body_hi):
            self.blocks.append(Block(tag, xml[cursor:s], xml[s:e]))
            cursor = e
        self.tail_gap = xml[cursor:self.body_hi]

    def _replay(self):
        self._load_blocks()
        for op in self.ops:
            self._apply(op)

    # ----- reading -----

    @property
    def block_count(self):
        return len(self.blocks)

    def block_text(self, index):
        block = self.blocks[index]
        if block.tag == "w:p":
            return block.para.text
        if block.tag == "w:tbl":
            cells = re.findall(r"<w:tc\b.*?</w:tc>", block.xml, re.S)
            return " | ".join(container_text(c, 0, len(c)) for c in cells)
        return container_text(block.xml, 0, len(block.xml))

    def block_kind(self, index):
        block = self.blocks[index]
        if block.tag == "w:tbl":
            return "table"
        if block.tag != "w:p":
            return "other"
        style = re.search(r'<w:pStyle w:val="([^"]+)"', block.xml)
        if style:
            name = style.group(1)
            match = re.match(r"(?i)heading\s*(\d)", name)
            if match:
                return f"heading{match.group(1)}"
            if re.match(r"(?i)(title|subtitle)", name):
                return "heading1"
        text = block.para.text.strip()
        runs = [s for s in block.para.segs if s.kind == "text" and s.text.strip()]
        if text and len(text) < 90 and runs and all(
                re.search(r"<w:b/>|<w:b w:val=\"(1|true)\"", s.rpr)
                for s in runs):
            return "heading2"
        return "paragraph"

    def block_editable(self, index):
        return self.blocks[index].tag == "w:p"

    def outline(self):
        result = []
        for index in range(len(self.blocks)):
            kind = self.block_kind(index)
            if kind.startswith("heading"):
                result.append((index, int(kind[7:] or 1), self.block_text(index).strip()))
        return result

    def search(self, query, limit=200):
        query = query.strip().lower()
        if not query:
            return []
        hits = []
        for index in range(len(self.blocks)):
            text = self.block_text(index)
            low = text.lower()
            at = low.find(query)
            while at != -1 and len(hits) < limit:
                a = max(0, at - 30)
                b = min(len(text), at + len(query) + 30)
                hits.append((index, at, text[a:b]))
                at = low.find(query, at + 1)
        return hits

    def existing_reference_headings(self):
        names = ("references", "reference list", "bibliography", "works cited")
        found = []
        for index in range(len(self.blocks)):
            if self.blocks[index].tag == "w:p" and \
                    self.block_text(index).strip().lower() in names:
                found.append(index)
        return found

    def has_zotero_fields(self):
        return bool(re.search(r"ZOTERO_ITEM|ZOTERO_BIBL|CSL_CITATION|CSL_BIBLIOGRAPHY",
                              self.original_xml))

    # ----- editing -----

    def insert_citation(self, block, start, end, items, style="parenthetical",
                        page="", prefix=""):
        """Put a citation at [start,end) of a paragraph (end==start inserts)."""
        items = unique_items(items)
        if not items:
            raise ValueError("Choose at least one reference.")
        para = self._para(block)
        text = para.text
        before = text[start - 1] if start > 0 else ""
        after = text[end] if end < len(text) else ""
        lead, trail = smart_spacing(before, after, "")

        op = {
            "type": "cite", "block": block, "start": start, "end": end,
            "items": items, "style": style, "page": page, "prefix": prefix,
            "lead": lead, "trail": trail, "n": self.next_citation,
            "bm_ids": list(range(self.next_bookmark_id,
                                 self.next_bookmark_id + len(items))),
            "text": build_citation_text(items, style, page, prefix),
        }
        self._apply(op)                      # raises NotEditable if not allowed
        self.ops.append(op)
        self.next_citation += 1
        self.next_bookmark_id += len(items)
        return op["n"]

    def delete_range(self, block, start, end, tidy=True):
        para = self._para(block)
        text = para.text
        if tidy and start > 0:
            before = text[start - 1]
            after = text[end] if end < len(text) else ""
            if before == " " and (after == "" or after in " .,;:!?)"):
                start -= 1
        op = {"type": "delete", "block": block, "start": start, "end": end}
        self._apply(op)
        self.ops.append(op)

    def undo(self):
        if not self.ops:
            return False
        op = self.ops.pop()
        if op["type"] == "cite":
            self.next_citation -= 1
            self.next_bookmark_id -= len(op["items"])
        self._replay()
        return True

    @property
    def can_undo(self):
        return bool(self.ops)

    def _para(self, block):
        if not 0 <= block < len(self.blocks) or self.blocks[block].tag != "w:p":
            raise NotEditable("Blaykie can only edit paragraphs.")
        return self.blocks[block].para

    def _apply(self, op):
        block = self.blocks[op["block"]]
        para = self._para(op["block"])
        start, end = op["start"], op["end"]

        if op["type"] == "delete":
            if start == end:
                return
            block.set_xml(para.replace(start, end, ""))
            return

        rpr = para.format_source(start, end)
        pieces = [make_run(rpr, op["lead"])]
        for bm_id, item in zip(op["bm_ids"], op["items"]):
            name = bookmark_name(item, op["n"])
            pieces.append(f'<w:bookmarkStart w:id="{bm_id}" w:name="{name}"/>')
        pieces.append(make_run(rpr, op["text"]))
        for bm_id in reversed(op["bm_ids"]):
            pieces.append(f'<w:bookmarkEnd w:id="{bm_id}"/>')
        pieces.append(make_run(rpr, op["trail"]))
        block.set_xml(para.replace(start, end, "".join(pieces)))

    # ----- cited references -----

    def cited_items(self):
        items = []
        for op in self.ops:
            if op["type"] == "cite":
                items.extend(op["items"])
        return unique_items(items)

    def citations(self):
        result = []
        for op in self.ops:
            if op["type"] == "cite":
                result.append((op["n"], [i.get("key") for i in op["items"]], op["text"]))
        return result

    def bibliography_text(self):
        items = self.cited_items()
        if not items:
            return ""
        suffixes = year_suffixes(items)
        lines = []
        for item in sorted_items(items):
            parts = apa_reference_parts(item.get("data", {}),
                                        suffixes.get(item.get("key") or id(item), ""))
            lines.append("".join(t for t, _ in parts))
        return "\n\n".join(lines)

    # ----- export -----

    def _body_text_runs(self):
        for block in self.blocks:
            if block.tag == "w:p":
                for seg in block.para.segs:
                    if seg.kind == "text" and len(seg.text) >= 30:
                        yield block, seg

    def _base_run_format(self):
        for block, seg in self._body_text_runs():
            fonts = re.search(r"<w:rFonts\b[^>]*/>", seg.rpr)
            size = re.search(r"<w:sz\b[^>]*/>", seg.rpr)
            size_cs = re.search(r"<w:szCs\b[^>]*/>", seg.rpr)
            return (fonts.group(0) if fonts else "",
                    (size.group(0) if size else "") + (size_cs.group(0) if size_cs else ""))
        return "", ""

    def _body_line_spacing(self):
        values = []
        for block, seg in self._body_text_runs():
            match = re.search(r'<w:spacing\b[^>]*\bw:line="(\d+)"', block.xml)
            if match:
                values.append(int(match.group(1)))
        if values:
            return Counter(values).most_common(1)[0][0]
        return 480

    def _bibliography_xml(self, items, line, heading, page_break, bm_id):
        fonts, size = self._base_run_format()
        suffixes = year_suffixes(items)

        def run(text, italic=False, bold=False):
            rpr = "<w:rPr>" + fonts + ("<w:b/>" if bold else "") \
                + ("<w:i/>" if italic else "") + size + "</w:rPr>"
            return make_run(rpr, text)

        spacing = f'<w:spacing w:before="0" w:after="0" w:line="{line}" w:lineRule="auto"/>'
        out = [f'<w:bookmarkStart w:id="{bm_id}" w:name="{BIB_BOOKMARK}"/>']
        out.append("<w:p><w:pPr>" + ("<w:pageBreakBefore/>" if page_break else "")
                   + spacing + '<w:jc w:val="center"/></w:pPr>'
                   + run(heading, bold=True) + "</w:p>")
        for item in sorted_items(items):
            suffix = suffixes.get(item.get("key") or id(item), "")
            parts = apa_reference_parts(item.get("data", {}), suffix)
            out.append("<w:p><w:pPr>" + spacing
                       + '<w:ind w:left="720" w:hanging="720"/></w:pPr>'
                       + "".join(run(text, italic) for text, italic in parts)
                       + "</w:p>")
        out.append(f'<w:bookmarkEnd w:id="{bm_id}"/>')
        return out

    def build_xml(self, bibliography=True, heading="References", line="match",
                  page_break=True):
        """The final document.xml text (citations refreshed, bibliography added)."""
        self._replay()
        items = self.cited_items()

        # refresh citation texts so a/b suffixes are right across the paper
        xml_blocks = [b.gap + b.xml for b in self.blocks]
        joined = self.prefix + "".join(xml_blocks) + self.tail_gap + self.suffix
        for op in self.ops:
            if op["type"] != "cite":
                continue
            fresh = build_citation_text(op["items"], op["style"], op["page"],
                                        op["prefix"], context=items)
            name = bookmark_name(op["items"][0], op["n"])
            pattern = re.compile(
                r'(<w:bookmarkStart w:id="\d+" w:name="' + re.escape(name) + r'"/>'
                r'(?:<w:bookmarkStart [^>]*/>)*<w:r>(?:<w:rPr>.*?</w:rPr>)?'
                r'<w:t xml:space="preserve">)(.*?)(</w:t></w:r>)', re.S)
            joined = pattern.sub(
                lambda m: m.group(1) + escape(fresh) + m.group(3), joined, count=1)

        if not bibliography or not items:
            return joined

        # rebuild with the refreshed text, then add or replace the bibliography
        saved = (self.original_xml, self.prefix, self.suffix, self.body_lo,
                 self.body_hi, self.blocks, self.tail_gap)
        self.original_xml = joined
        self._split_body()
        self._load_blocks()
        try:
            spacing = self._body_line_spacing() if line == "match" else int(line)
            bm_id = max(
                [int(i) for i in re.findall(
                    r'<w:bookmarkStart\b[^>]*\bw:id="(\d+)"', joined)] + [0]) + 1
            new_blocks = self._bibliography_xml(items, spacing, heading,
                                                page_break, bm_id)

            blocks = self.blocks
            start_at = None
            end_at = None
            for index, block in enumerate(blocks):
                if BIB_BOOKMARK in block.xml and block.tag == "w:bookmarkStart":
                    start_at = index
                if start_at is not None and block.tag == "w:bookmarkEnd" \
                        and end_at is None and index > start_at:
                    end_at = index
            if start_at is not None and end_at is not None:
                before = blocks[:start_at]
                after = blocks[end_at + 1:]
            else:
                if blocks and blocks[-1].tag == "w:sectPr":
                    before, after = blocks[:-1], blocks[-1:]
                else:
                    before, after = blocks, []
            middle = [Block("w:p", "", x) for x in new_blocks]
            merged = before + middle + after
            result = (self.prefix + "".join(b.gap + b.xml for b in merged)
                      + self.tail_gap + self.suffix)
        finally:
            (self.original_xml, self.prefix, self.suffix, self.body_lo,
             self.body_hi, self.blocks, self.tail_gap) = saved
        return result

    def export(self, out_path, **options):
        if _same_file(out_path, self.path):
            raise ValueError("Choose a different file name. The original is never changed.")
        xml = self.build_xml(**options)
        data = xml.encode("utf-8")
        if self.bom:
            data = b"\xef\xbb\xbf" + data
        with zipfile.ZipFile(self.path) as source, \
                zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as target:
            for info in source.infolist():
                content = data if info.filename == "word/document.xml" \
                    else source.read(info.filename)
                target.writestr(info, content, compress_type=zipfile.ZIP_DEFLATED)
        return out_path


def _same_file(a, b):
    import os
    return os.path.abspath(a) == os.path.abspath(b)
