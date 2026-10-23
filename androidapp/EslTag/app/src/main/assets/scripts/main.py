# Dispatcher: call screen > inbox screen > weather. Edit freely (no rebuild).
# Kotlin writes inbox.json (state) and reads config.json (who to follow, timing).
import json, os, time
import esl_lib as E
import weather

K, W, Y, R = (E.RGB[n] for n in ("black", "white", "yellow", "red"))
SW, SH = E.SW, E.SH


def load(name, default):
    try:
        with open(os.path.join(E.DIR, name), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def trunc(d, s, size, maxw):
    f, cut = E.font(size), False
    while s and d.textlength(E.shape(s), font=f) > maxw:
        s, cut = s[:-1], True
    return s + (".." if cut else "")


def hhmm(ms):
    return time.strftime("%H:%M", time.localtime(ms / 1000.0))


def call_screen_left(d, x0, y0, w, h, r):
    d.rectangle([x0, y0, x0 + w - 1, y0 + 15], fill=R)
    d.text((x0 + 4, y0 + 8), E.shape("INCOMING CALL"), font=E.font(10), fill=W, anchor="lm")
    name = r.get("who") or "Unknown"
    d.text((x0 + w // 2, y0 + 48), E.shape(name), font=E.font(E.fit(d, name, w - 8, 40, 30)), fill=K, anchor="mm")
    d.rectangle([x0, y0 + h - 20, x0 + w - 1, y0 + h - 1], fill=Y)
    d.text((x0 + w // 2, y0 + h - 10), "WhatsApp" if r.get("src") == "whatsapp" else "Phone",
           font=E.font(10), fill=K, anchor="mm")


def inbox_screen_left(d, x0, y0, w, h, chats, missed, show_text):
    d.rectangle([x0, y0, x0 + w - 1, y0 + 15], fill=R)
    d.text((x0 + 4, y0 + 8), E.shape("Messages"), font=E.font(10), fill=W, anchor="lm")
    d.text((x0 + w - 4, y0 + 8), time.strftime("%H:%M"), font=E.font(9), fill=W, anchor="rm")
    band = 22 if missed else 0
    top, bottom = y0 + 18, y0 + h - band - 2
    rowh = 28 if show_text else 18
    n = max(1, (bottom - top) // rowh)
    rows = chats if len(chats) <= n else chats[:n - 1]
    y = top
    for c in rows:
        d.text((x0 + 4, y + 8), E.shape(trunc(d, c["who"], 11, w - 38)), font=E.font(11), fill=K, anchor="lm")
        d.rectangle([x0 + w - 24, y + 1, x0 + w - 4, y + 15], fill=R)
        d.text((x0 + w - 14, y + 8), str(c["count"]), font=E.font(9), fill=W, anchor="mm")
        if show_text and c.get("last"):
            d.text((x0 + 4, y + 21), E.shape(trunc(d, c["last"], 9, w - 8)), font=E.font(9), fill=K, anchor="lm")
        y += rowh
    if len(chats) > len(rows):
        d.text((x0 + 4, y + 8), E.shape("+%d more" % (len(chats) - len(rows))), font=E.font(9), fill=R, anchor="lm")
    if missed:
        d.rectangle([x0, y0 + h - band, x0 + w - 1, y0 + h - 1], fill=Y)
        s = "Missed: " + ", ".join("%s %s" % (m["who"], hhmm(m["ts"])) for m in missed[:2])
        d.text((x0 + 4, y0 + h - band // 2), E.shape(trunc(d, s, 9, w - 8)), font=E.font(9), fill=K, anchor="lm")


def render():
    ib, cfg = load("inbox.json", {}), load("config.json", {})
    limit = time.time() * 1000 - cfg.get("missed_hours", 12) * 3600 * 1000
    missed = [m for m in ib.get("missed", []) if m["ts"] >= limit]
    chats = ib.get("chats", [])
    ringing = ib.get("ringing")

    if not ringing and not chats and not missed:
        return weather.render()

    im, d = E.new()
    try:
        w_data = weather.get(weather.URL)
    except Exception:
        w_data = None

    # Vertical divider
    d.line([(146, 0), (146, SH - 1)], fill=K)

    # Right column: Compact Weather (x=147, w=103)
    weather.draw_compact(d, 147, 0, 103, SH, w_data)

    # Left column: Calls / Messages (x=0, w=146)
    if ringing:
        call_screen_left(d, 0, 0, 146, SH, ringing)
    else:
        inbox_screen_left(d, 0, 0, 146, SH, chats, missed, cfg.get("show_text", True))

    return E.to_payload(im)
