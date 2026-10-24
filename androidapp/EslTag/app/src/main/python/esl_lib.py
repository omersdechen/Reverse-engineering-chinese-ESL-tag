"""ESL helper library (bundled in the APK). Your editable scripts do `import esl_lib as E`."""
import os, struct, sys, runpy, traceback, re
from PIL import Image, ImageDraw, ImageFont

SW, SH = 250, 122            # screen, landscape (what you draw on)
W, H = 122, 250              # stored portrait raster
RGB = {"black": (0, 0, 0), "white": (255, 255, 255), "yellow": (230, 200, 0), "red": (200, 0, 0)}
ORDER = ["black", "white", "yellow", "red"]
HW_CODE = [0, 1, 2, 3]       # logical colour (ORDER index) -> 2-bit value on the tag. Fix colours HERE.
HDR = bytes.fromhex("5854454b00043f0400001304010000001100000000000000000000007a000000fa010000" "12")
DIR = ""                     # scripts folder, set by run_scripts()

FONTS = ["/system/fonts/NotoSansHebrew-Bold.ttf", "/system/fonts/NotoSansHebrew-Regular.ttf",
         "/system/fonts/DroidSansHebrew.ttf", "/system/fonts/SecularOne-Regular.ttf",
         "/system/fonts/Roboto-Bold.ttf", "/system/fonts/Roboto-Regular.ttf",
         "/system/fonts/DroidSans-Bold.ttf", "/system/fonts/DroidSans.ttf",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
_fcache = {}
_emoji_font_cache = {}


def emoji_font(size):
    if size in _emoji_font_cache:
        return _emoji_font_cache[size]
    candidates = [
        "/system/fonts/NotoColorEmoji.ttf",
        "/system/fonts/NotoColorEmojiFlags.ttf",
        "/system/fonts/AndroidEmoji.ttf",
    ]
    for p in candidates:
        if os.path.exists(p):
            try:
                _emoji_font_cache[size] = ImageFont.truetype(p, size)
                return _emoji_font_cache[size]
            except Exception:
                continue
    _emoji_font_cache[size] = None
    return None


def is_emoji(c):
    cp = ord(c)
    return (
        0x1F600 <= cp <= 0x1F64F or
        0x1F300 <= cp <= 0x1F5FF or
        0x1F680 <= cp <= 0x1F6FF or
        0x1F1E6 <= cp <= 0x1F1FF or
        0x2600 <= cp <= 0x27BF or
        0x2300 <= cp <= 0x23FF or
        0x2B50 <= cp <= 0x2B55 or
        0x1F900 <= cp <= 0x1F9FF or
        0x1FA70 <= cp <= 0x1FAFF
    )


def remove_unsupported_symbols(s):
    return "".join(c for c in s if ord(c) >= 32 or c in "\n\r\t")


def font(size):
    if size in _fcache:
        return _fcache[size]
    cands = [os.path.join(DIR, "font.ttf")] + FONTS
    try:
        cands += sorted(os.path.join("/system/fonts", f) for f in os.listdir("/system/fonts")
                        if f.lower().endswith(".ttf"))
    except OSError:
        pass
    for p in cands:
        try:
            _fcache[size] = ImageFont.truetype(p, size)
            return _fcache[size]
        except (OSError, IOError):
            continue
    raise RuntimeError("no font found; copy a .ttf to the scripts folder as font.ttf")


def is_hebrew(s):
    return any("\u0590" <= c <= "\u05ff" for c in s)


def shape(s):
    """Bi-directional Hebrew shaper for RTL text rendering in Pillow."""
    if not is_hebrew(s):
        return s
    tokens = re.findall(r'[\u0590-\u05ff]+|[a-zA-Z0-9]+|[^\w\s]|\s+', s)
    shaped_tokens = []
    for t in tokens:
        if is_hebrew(t):
            shaped_tokens.append(t[::-1])
        else:
            shaped_tokens.append(t)
    return "".join(reversed(shaped_tokens))


def new(bg="white"):
    im = Image.new("RGB", (SW, SH), RGB[bg])
    d = ImageDraw.Draw(im)
    d.fontmode = "1"                     # no antialiasing: exact palette colours
    return im, d


def draw_emoji(im, xy, emoji_str, ef, fill="black"):
    """Renders color emoji from NotoColorEmoji.ttf onto a temporary RGBA canvas and pastes onto RGB image im."""
    if not ef or not emoji_str or not im:
        return 0
    try:
        dummy_im = Image.new("RGBA", (1, 1))
        dummy_d = ImageDraw.Draw(dummy_im)
        bbox = dummy_d.textbbox((0, 0), emoji_str, font=ef)
        if not bbox:
            return 0

        w = max(1, bbox[2] - bbox[0] + 6)
        h = max(1, bbox[3] - bbox[1] + 6)

        emoji_im = Image.new("RGBA", (w, h), (255, 255, 255, 0))
        emoji_d = ImageDraw.Draw(emoji_im)
        emoji_d.text((-bbox[0] + 3, -bbox[1] + 3), emoji_str, font=ef, fill=(0, 0, 0, 255))

        bg = Image.new("RGB", (w, h), (255, 255, 255))
        bg.paste(emoji_im, (0, 0), emoji_im)

        px = bg.load()
        for py in range(h):
            for px_x in range(w):
                r, g, b = px[px_x, py]
                if r < 120 and g < 120 and b < 120:
                    px[px_x, py] = (0, 0, 0)
                elif r > 150 and g < 100 and b < 100:
                    px[px_x, py] = (200, 0, 0)
                elif r > 180 and g > 150 and b < 80:
                    px[px_x, py] = (230, 200, 0)
                else:
                    px[px_x, py] = (255, 255, 255)

        x, y = int(xy[0]), int(xy[1])
        im.paste(bg, (x, y))
        return w - 4
    except Exception:
        return 0


def get_text_width(s, size):
    """Calculates true pixel width of text containing mixed normal text, Hebrew, and emojis."""
    f = font(size)
    ef = emoji_font(size)

    total_w = 0
    tokens = re.findall(r'[\U00010000-\U0010ffff\u2600-\u27bf\u2300-\u23ff]+|[^\U00010000-\U0010ffff\u2600-\u27bf\u2300-\u23ff]+', s)
    for tok in tokens:
        if any(is_emoji(c) for c in tok) and ef:
            try:
                bbox = ef.getbbox(tok)
                if bbox:
                    total_w += (bbox[2] - bbox[0])
                else:
                    total_w += size
            except Exception:
                total_w += size
        else:
            try:
                total_w += f.getlength(shape(tok))
            except Exception:
                total_w += size * len(tok)
    return total_w


def draw_text_smart(d, xy, s, size, fill="black", anchor="la"):
    """Smart text renderer with bi-directional Hebrew and Emoji support."""
    s = remove_unsupported_symbols(s)
    f = font(size)
    ef = emoji_font(size)

    if not ef or not any(is_emoji(c) for c in s):
        d.text(xy, shape(s), font=f, fill=RGB[fill], anchor=anchor)
        return

    x, y = xy
    shaped_s = shape(s)
    tokens = re.findall(r'[\U00010000-\U0010ffff\u2600-\u27bf\u2300-\u23ff]+|[^\U00010000-\U0010ffff\u2600-\u27bf\u2300-\u23ff]+', shaped_s)

    curr_x = x
    im = getattr(d, "_image", None)

    for tok in tokens:
        if any(is_emoji(c) for c in tok) and ef and im:
            ew = draw_emoji(im, (curr_x, y - 2), tok, ef, fill=fill)
            curr_x += max(ew, size)
        else:
            d.text((curr_x, y), tok, font=f, fill=RGB[fill], anchor=anchor)
            try:
                curr_x += d.textlength(tok, font=f)
            except Exception:
                curr_x += size * len(tok)


def text(d, xy, s, size, fill="black", anchor="la"):
    draw_text_smart(d, xy, s, size, fill, anchor)


def bar(d, s, size=16, bg="red", fg="white", h=26):
    d.rectangle([0, 0, SW - 1, h - 1], fill=RGB[bg])
    d.text((SW // 2, h // 2), shape(s), font=font(size), fill=RGB[fg], anchor="mm")


def fit(d, s, maxw, maxh, max_size=90):
    """Largest font size whose text fits the box."""
    for size in range(max_size, 7, -1):
        l, t, r, b = d.textbbox((0, 0), shape(s), font=font(size))
        if r - l <= maxw and b - t <= maxh:
            return size
    return 8


def dither(im):
    """Floyd-Steinberg onto the 4 tag colours, for photos."""
    pal = Image.new("P", (1, 1))
    pal.putpalette(sum((list(RGB[n]) for n in ORDER), []) + [0] * (768 - 12))
    return im.convert("RGB").quantize(palette=pal, dither=3).convert("RGB")


def rle(data, max_run=1):
    out, i = bytearray(), 0
    while i < len(data):
        j = i
        while j < len(data) and data[j] == data[i] and j - i < max_run:
            j += 1
        out += bytes([data[i], j - i])
        i = j
    return bytes(out)


def to_payload(im, max_run=1):
    """250x122 RGB image -> tag payload. max_run=1 is the setting that gave correct colours."""
    lut = {RGB[n]: i for i, n in enumerate(ORDER)}
    r = im.convert("RGB").rotate(-90, expand=True)
    assert r.size == (W, H), r.size
    px = r.load()

    def idx(c):
        if c in lut:
            return lut[c]
        return min(range(4), key=lambda i: sum((c[k] - RGB[ORDER[i]][k]) ** 2 for k in range(3)))

    raw = bytearray()
    for y in range(H):
        row = [idx(px[x, y]) for x in range(W)] + [1, 1]       # pad 122 -> 124 with white
        for x in range(0, W + 2, 4):
            a, b, c, d = (HW_CODE[v] for v in row[x:x + 4])
            raw.append(a << 6 | b << 4 | c << 2 | d)
    body = rle(raw, max_run)
    hdr = bytearray(HDR)
    hdr[8:12] = struct.pack(">I", len(HDR) + len(body))
    if DIR:
        try:
            im.resize((SW * 2, SH * 2), Image.NEAREST).save(os.path.join(DIR, "preview.png"))
        except Exception:
            pass
    return bytes(hdr) + body


def run_scripts(path):
    """Called by the app: run <path>/main.py fresh and return render()'s payload bytes."""
    global DIR
    DIR = path
    try:
        for n, m in list(sys.modules.items()):          # pick up edited helper modules
            f = getattr(m, "__file__", None) or ""
            if f.startswith(path):
                del sys.modules[n]
        if path not in sys.path:
            sys.path.insert(0, path)
        g = runpy.run_path(os.path.join(path, "main.py"))
        out = g["render"]()
        if not isinstance(out, (bytes, bytearray)):
            raise TypeError("render() must return payload bytes (use E.to_payload(im))")
        return bytes(out)
    except Exception:
        raise RuntimeError(traceback.format_exc())
