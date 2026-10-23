# Herzliya weather + rain. Edit in-app or over USB. render() must return E.to_payload(image).
import json, math, os, time, urllib.request
import esl_lib as E
from PIL import Image, ImageOps

LAT, LON, CITY = 32.1663, 34.8433, "Herzliya"
BG_MODE = "color"        # photo background: "color" = 4-colour dither, "bw" = cleaner black/white
RAIN_HOURS = 12          # hours shown in the rain bars

K, W, Y, R = (E.RGB[n] for n in ("black", "white", "yellow", "red"))
SW, SH = E.SW, E.SH
PHOTO = False

URL = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&timezone=auto&forecast_days=2"
       "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,"
       "wind_speed_10m,wind_direction_10m,is_day"
       "&hourly=precipitation_probability,precipitation"
       "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,"
       "precipitation_probability_max,sunrise,sunset,uv_index_max" % (LAT, LON))

WORDS = {0: "Clear", 1: "Mostly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Rime fog",
         51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle", 56: "Freezing drizzle",
         57: "Freezing drizzle", 61: "Light rain", 63: "Rain", 65: "Heavy rain", 66: "Freezing rain",
         67: "Freezing rain", 71: "Light snow", 73: "Snow", 75: "Heavy snow", 77: "Snow grains",
         80: "Showers", 81: "Showers", 82: "Heavy showers", 85: "Snow showers", 86: "Snow showers",
         95: "Thunderstorm", 96: "Thunder + hail", 99: "Thunder + hail"}


def kind(c):
    if c == 0: return "clear"
    if c in (1, 2): return "partly"
    if c in (45, 48): return "fog"
    if 51 <= c <= 67 or 80 <= c <= 82: return "rain"
    if 71 <= c <= 77 or c in (85, 86): return "snow"
    if c >= 95: return "storm"
    return "cloud"


# ---------------------------------------------------------------- drawing helpers
def T(d, xy, s, size, fill=K, anchor="lm", maxw=None):
    """Text; shrinks to maxw; gets a white halo when drawn over a photo."""
    while maxw and size > 8 and d.textlength(E.shape(s), font=E.font(size)) > maxw:
        size -= 1
    kw = dict(stroke_width=2, stroke_fill=W) if PHOTO else {}
    d.text(xy, E.shape(s), font=E.font(size), fill=fill, anchor=anchor, **kw)


def disc(d, cx, cy, r, fill, outline=None):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fill, outline=outline)


def sun_small(d, cx, cy, r):
    for a in range(0, 360, 45):
        c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
        d.line([cx + c * (r + 2), cy + s * (r + 2), cx + c * (r + 5), cy + s * (r + 5)], fill=Y, width=1)
    disc(d, cx, cy, r, Y, K)


def moon_small(d, cx, cy, r):
    disc(d, cx, cy, r, Y, K)
    disc(d, cx + r * 0.55, cy - r * 0.35, r * 0.85, W)


def cloud_small(d, x, y, w):
    parts = [(0.27, 0.50, 0.20), (0.50, 0.36, 0.27), (0.76, 0.52, 0.19)]
    for grow, col in ((1, K), (0, W)):
        for cx, cy, r in parts:
            disc(d, x + cx * w, y + cy * w, r * w + grow, col)
        d.rectangle([x + 0.27 * w, y + 0.50 * w, x + 0.76 * w, y + 0.71 * w + grow], fill=col)


def icon_compact(d, k, day, x, y):
    """Compact 30x30 px box at (x, y)."""
    if k == "clear":
        (sun_small if day else moon_small)(d, x + 15, y + 15, 8)
    elif k == "partly":
        (sun_small if day else moon_small)(d, x + 20, y + 9, 6)
        cloud_small(d, x, y + 8, 28)
    elif k == "cloud":
        cloud_small(d, x + 1, y + 5, 28)
    else:
        cloud_small(d, x + 2, y + 2, 26)
        if k == "rain":
            for i in range(3):
                d.line([x + 9 + i * 6, y + 21, x + 7 + i * 6, y + 27], fill=K, width=1)
        elif k == "snow":
            for i in range(3):
                disc(d, x + 9 + i * 6, y + 23 + (i % 2) * 2, 1, K)
        elif k == "storm":
            d.polygon([(x + 16, y + 18), (x + 11, y + 24), (x + 15, y + 24), (x + 13, y + 29),
                       (x + 20, y + 21), (x + 16, y + 21), (x + 18, y + 18)], fill=Y, outline=K)
        elif k == "fog":
            for i in range(3):
                d.line([x + 3, y + 20 + i * 3, x + 27, y + 20 + i * 3], fill=K, width=1)


def sun(d, cx, cy, r):
    for a in range(0, 360, 45):
        c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
        d.line([cx + c * (r + 3), cy + s * (r + 3), cx + c * (r + 8), cy + s * (r + 8)], fill=Y, width=2)
    disc(d, cx, cy, r, Y, K)


def moon(d, cx, cy, r):
    disc(d, cx, cy, r, Y, K)
    disc(d, cx + r * 0.55, cy - r * 0.35, r * 0.85, W)


def cloud(d, x, y, w):
    parts = [(0.27, 0.50, 0.20), (0.50, 0.36, 0.27), (0.76, 0.52, 0.19)]
    for grow, col in ((2, K), (0, W)):
        for cx, cy, r in parts:
            disc(d, x + cx * w, y + cy * w, r * w + grow, col)
        d.rectangle([x + 0.27 * w, y + 0.50 * w, x + 0.76 * w, y + 0.71 * w + grow], fill=col)


def icon(d, k, day, x, y):
    """About 56x54 px box at (x, y)."""
    if k == "clear":
        (sun if day else moon)(d, x + 28, y + 27, 14)
    elif k == "partly":
        (sun if day else moon)(d, x + 38, y + 15, 11)
        cloud(d, x + 1, y + 14, 46)
    elif k == "cloud":
        cloud(d, x + 2, y + 8, 52)
    else:
        cloud(d, x + 5, y + 2, 46)
        if k == "rain":
            for i in range(3):
                d.line([x + 17 + i * 11, y + 38, x + 13 + i * 11, y + 48], fill=K, width=2)
        elif k == "snow":
            for i in range(3):
                disc(d, x + 16 + i * 12, y + 43 + (i % 2) * 5, 2, K)
        elif k == "storm":
            d.polygon([(x + 30, y + 33), (x + 21, y + 46), (x + 28, y + 46), (x + 24, y + 56),
                       (x + 37, y + 41), (x + 30, y + 41), (x + 34, y + 33)], fill=Y, outline=K)
        elif k == "fog":
            for i in range(3):
                d.line([x + 6, y + 38 + i * 6, x + 52, y + 38 + i * 6], fill=K, width=2)


# ---------------------------------------------------------------- photo background
def bg_path(k):
    for n in ("bg_" + k, "bg"):
        for ext in (".png", ".jpg", ".jpeg"):
            p = os.path.join(E.DIR, n + ext)
            if os.path.exists(p):
                return p


def photo(path):
    im = ImageOps.autocontrast(ImageOps.fit(Image.open(path).convert("RGB"), (SW, SH), Image.LANCZOS), cutoff=1)
    return im.convert("L").convert("1").convert("RGB") if BG_MODE == "bw" else E.dither(im)


# ---------------------------------------------------------------- render
def get(url):
    with urllib.request.urlopen(url, timeout=20) as r:
        return json.load(r)


def render():
    global PHOTO
    j = get(URL)
    c, h, dd = j["current"], j["hourly"], j["daily"]
    code, day = c["weather_code"], c.get("is_day", 1)
    k = kind(code)
    path = bg_path(k)
    PHOTO = bool(path)
    im, d = E.new()
    if path:
        im.paste(photo(path))

    # top bar
    t = time.strptime(c["time"], "%Y-%m-%dT%H:%M")
    d.rectangle([0, 0, SW - 1, 15], fill=R)
    d.text((6, 8), CITY, font=E.font(10), fill=W, anchor="lm")
    d.text((SW - 6, 8), time.strftime("%a %d/%m  %H:%M", t), font=E.font(9), fill=W, anchor="rm")

    # main block: icon | temperature | details
    tx = 6 if PHOTO else 62
    if not PHOTO:
        icon(d, k, day, 4, 23)
    temp = "%.0f\u00b0" % c["temperature_2m"]
    T(d, (tx, 42), temp, E.fit(d, temp, 80, 42, 60), K, "lm")
    T(d, (tx, 69), "H %.0f\u00b0  L %.0f\u00b0" % (dd["temperature_2m_max"][0], dd["temperature_2m_min"][0]), 12, K, "lm", 80)
    cx = 148
    if PHOTO:
        d.rectangle([cx - 5, 22, SW - 2, 78], fill=W, outline=K)
    deg = c.get("wind_direction_10m") or 0
    compass = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][int((deg + 22.5) // 45) % 8]
    T(d, (cx, 30), WORDS.get(code, "Code %d" % code), 14, R, "lm", SW - 6 - cx)
    T(d, (cx, 45), "Feels like %.0f\u00b0" % c["apparent_temperature"], 11, K, "lm", SW - 6 - cx)
    T(d, (cx, 58), "Wind %.0f km/h %s" % (c["wind_speed_10m"], compass), 11, K, "lm", SW - 6 - cx)
    T(d, (cx, 71), "Hum %.0f%%  UV %.0f" % (c["relative_humidity_2m"], dd["uv_index_max"][0]), 11, K, "lm", SW - 6 - cx)

    # rain strip
    d.rectangle([0, 80, SW - 1, SH - 1], fill=W)
    d.line([(0, 80), (SW, 80)], fill=K)
    pmax, psum = dd["precipitation_probability_max"][0] or 0, dd["precipitation_sum"][0] or 0
    rain = psum > 0 or pmax >= 20
    sun_s = "Sun %s-%s" % (dd["sunrise"][0][11:16], dd["sunset"][0][11:16])
    wsun = d.textlength(sun_s, font=E.font(11))
    T(d, (6, 89), "Rain %.0f%% today, %.1f mm" % (pmax, psum) if rain else "No rain today", 11,
      R if psum > 0 else K, "lm", SW - 20 - wsun)
    T(d, (SW - 6, 89), sun_s, 11, K, "rm")
    now = c["time"][:13]
    i0 = next((i for i, x in enumerate(h["time"]) if x[:13] == now), 0)
    hrs = [(int(h["time"][i][11:13]), h["precipitation_probability"][i] or 0, h["precipitation"][i] or 0)
           for i in range(i0, min(i0 + RAIN_HOURS, len(h["time"])))]
    base, hmax, step = 110, 16, 19.5
    d.line([(4, base + 1), (SW - 5, base + 1)], fill=K)
    for n, (hh, p, mm) in enumerate(hrs):
        x = int(8 + n * step)
        d.rectangle([x, base - max(1, round(p / 100 * hmax)) + 1, x + 14, base], fill=R if mm >= 0.1 else K)
        if n % 3 == 0:
            d.text((x + 7, 117), "%02d" % hh, font=E.font(9), fill=K, anchor="mm")
    return E.to_payload(im)


def draw_compact(d, x0, y0, w, h, j=None):
    """Draws rich compact weather box containing all weather features inside rect [x0, y0, x0+w-1, y0+h-1]."""
    if j is None:
        try:
            j = get(URL)
        except Exception:
            j = None

    # 1. Top Header Bar (0..15)
    d.rectangle([x0, y0, x0 + w - 1, y0 + 15], fill=R)
    d.text((x0 + 4, y0 + 8), CITY, font=E.font(9), fill=W, anchor="lm")

    if not j:
        d.text((x0 + w // 2, y0 + h // 2), "No Weather", font=E.font(10), fill=K, anchor="mm")
        return

    c, h_data, dd = j["current"], j["hourly"], j["daily"]
    t = time.strptime(c["time"], "%Y-%m-%dT%H:%M")
    d.text((x0 + w - 4, y0 + 8), time.strftime("%H:%M", t), font=E.font(9), fill=W, anchor="rm")

    code, day = c["weather_code"], c.get("is_day", 1)
    k = kind(code)

    # 2. Main Weather Icon + Temp + Condition + High/Low (y = 16..56)
    icon_compact(d, k, day, x0 + 4, y0 + 18)
    temp_str = "%.0f\u00b0" % c["temperature_2m"]
    T(d, (x0 + 42, y0 + 25), temp_str, 18, K, "lm")
    cond_word = WORDS.get(code, "Clear").split()[0]
    T(d, (x0 + 42, y0 + 38), cond_word, 8, R, "lm", maxw=w - 44)
    hl_str = "H%.0f\u00b0 L%.0f\u00b0" % (dd["temperature_2m_max"][0], dd["temperature_2m_min"][0])
    T(d, (x0 + 42, y0 + 49), hl_str, 8, K, "lm", maxw=w - 44)

    # 3. Compact Weather Details Grid (y = 57..79)
    d.line([(x0 + 4, y0 + 56), (x0 + w - 5, y0 + 56)], fill=K)
    feels_str = "Feels %.0f\u00b0" % c["apparent_temperature"]
    uv_val = dd["uv_index_max"][0] or 0
    T(d, (x0 + 4, y0 + 63), feels_str, 8, K, "lm")
    T(d, (x0 + w - 4, y0 + 63), "UV %.0f" % uv_val, 8, K, "rm")

    deg = c.get("wind_direction_10m") or 0
    compass = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][int((deg + 22.5) // 45) % 8]
    wind_str = "W %.0fkm/h %s" % (c["wind_speed_10m"], compass)
    hum_str = "H %.0f%%" % c["relative_humidity_2m"]
    T(d, (x0 + 4, y0 + 73), wind_str, 8, K, "lm", maxw=w - 38)
    T(d, (x0 + w - 4, y0 + 73), hum_str, 8, K, "rm")

    # 4. Sun / Rain Summary Line (y = 80..91)
    d.line([(x0 + 4, y0 + 80), (x0 + w - 5, y0 + 80)], fill=K)
    pmax = dd["precipitation_probability_max"][0] or 0
    psum = dd["precipitation_sum"][0] or 0
    if psum > 0 or pmax >= 20:
        rain_str = "Rain %.0f%% %.1fmm" % (pmax, psum)
        T(d, (x0 + 4, y0 + 87), rain_str, 8, R, "lm", maxw=w - 8)
    else:
        sun_str = "Sun %s-%s" % (dd["sunrise"][0][11:16], dd["sunset"][0][11:16])
        T(d, (x0 + 4, y0 + 87), sun_str, 8, K, "lm", maxw=w - 8)

    # 5. Mini 6-Hour Hourly Rain Bar Chart (y = 93..121)
    d.line([(x0 + 4, y0 + 93), (x0 + w - 5, y0 + 93)], fill=K)
    now = c["time"][:13]
    i0 = next((i for i, x in enumerate(h_data["time"]) if x[:13] == now), 0)
    hrs = [(int(h_data["time"][i][11:13]), h_data["precipitation_probability"][i] or 0, h_data["precipitation"][i] or 0)
           for i in range(i0, min(i0 + 6, len(h_data["time"])))]

    base_y = y0 + 111
    bar_hmax = 12
    step = (w - 8) / max(1, len(hrs))
    for n, (hh, p, mm) in enumerate(hrs):
        bx = int(x0 + 4 + n * step)
        bw = max(2, int(step - 2))
        bh = max(1, round(p / 100.0 * bar_hmax))
        d.rectangle([bx, base_y - bh, bx + bw, base_y], fill=R if mm >= 0.1 else K)
        if n % 2 == 0:
            d.text((bx + bw // 2, y0 + 116), "%02d" % hh, font=E.font(7), fill=K, anchor="mm")
