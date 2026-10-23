"""ESL helper library (bundled in the APK). Your editable scripts do `import esl_lib as E`."""
import os, struct, sys, runpy, traceback
from PIL import Image, ImageDraw, ImageFont

SW, SH = 250, 122            # screen, landscape (what you draw on)
W, H = 122, 250              # stored portrait raster
RGB = {"black": (0, 0, 0), "white": (255, 255, 255), "yellow": (230, 200, 0), "red": (200, 0, 0)}
ORDER = ["black", "white", "yellow", "red"]
HW_CODE = [0, 1, 2, 3]       # logical colour (ORDER index) -> 2-bit value on the tag. Fix colours HERE.
HDR = bytes.fromhex("5854454b00043f0400001304010000001100000000000000000000007a000000fa010000" "12")
DIR = ""                     # scripts folder, set by run_scripts()

FONTS = ["/system/fonts/Roboto-Bold.ttf", "/system/fonts/Roboto-Regular.ttf",
         "/system/fonts/DroidSans-Bold.ttf", "/system/fonts/DroidSans.ttf",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
_fcache = {}


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


def shape(s):
    """Pure-Hebrew lines are reversed for left-to-right drawing."""
    return s[::-1] if any("\u0590" <= c <= "\u05ff" for c in s) else s


def new(bg="white"):
    im = Image.new("RGB", (SW, SH), RGB[bg])
    d = ImageDraw.Draw(im)
    d.fontmode = "1"                     # no antialiasing: exact palette colours
    return im, d


def text(d, xy, s, size, fill="black", anchor="la"):
    d.text(xy, shape(s), font=font(size), fill=RGB[fill], anchor=anchor)


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
