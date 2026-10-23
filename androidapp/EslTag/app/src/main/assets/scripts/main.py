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


def call_screen(r):
    im, d = E.new("red")
    d.text((SW // 2, 14), "INCOMING CALL", font=E.font(16), fill=W, anchor="mm")
    name = r.get("who") or "Unknown"
    d.text((SW // 2, 57), E.shape(name), font=E.font(E.fit(d, name, SW - 16, 56, 60)), fill=W, anchor="mm")
    d.rectangle([0, SH - 24, SW - 1, SH - 1], fill=Y)
    d.text((SW // 2, SH - 12), "WhatsApp" if r.get("src") == "whatsapp" else "Phone",
           font=E.font(14), fill=K, anchor="mm")
    return E.to_payload(im)


def inbox_screen(chats, missed, show_text):
    im, d = E.new()
    d.rectangle([0, 0, SW - 1, 19], fill=R)
    d.text((6, 10), "Messages", font=E.font(14), fill=W, anchor="lm")
    d.text((SW - 6, 10), time.strftime("%H:%M"), font=E.font(12), fill=W, anchor="rm")
    band = 26 if missed else 0
    top, bottom = 22, SH - band - 2
    rowh = 30 if show_text else 19
    n = max(1, (bottom - top) // rowh)
    rows = chats if len(chats) <= n else chats[:n - 1]
    if not chats:
        d.text((SW // 2, (top + bottom) // 2), "No new messages", font=E.font(14), fill=K, anchor="mm")
    y = top
    for c in rows:
        d.text((6, y + 9), E.shape(trunc(d, c["who"], 14, SW - 52)), font=E.font(14), fill=K, anchor="lm")
        d.rectangle([SW - 34, y + 1, SW - 6, y + 17], fill=R)
        d.text((SW - 20, y + 9), str(c["count"]), font=E.font(12), fill=W, anchor="mm")
        if show_text and c.get("last"):
            d.text((6, y + 23), E.shape(trunc(d, c["last"], 10, SW - 12)), font=E.font(10), fill=K, anchor="lm")
        y += rowh
    if len(chats) > len(rows):
        d.text((6, y + 9), "+%d more" % (len(chats) - len(rows)), font=E.font(12), fill=R, anchor="lm")
    if missed:
        d.rectangle([0, SH - band, SW - 1, SH - 1], fill=Y)
        s = "Missed: " + ", ".join("%s %s" % (m["who"], hhmm(m["ts"])) for m in missed[:3])
        d.text((6, SH - band // 2), E.shape(trunc(d, s, 12, SW - 12)), font=E.font(12), fill=K, anchor="lm")
    return E.to_payload(im)


def render():
    ib, cfg = load("inbox.json", {}), load("config.json", {})
    if ib.get("ringing"):
        return call_screen(ib["ringing"])
    limit = time.time() * 1000 - cfg.get("missed_hours", 12) * 3600 * 1000
    missed = [m for m in ib.get("missed", []) if m["ts"] >= limit]
    chats = ib.get("chats", [])
    if not chats and not missed:
        return weather.render()
    return inbox_screen(chats, missed, cfg.get("show_text", True))
