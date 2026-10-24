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
    cut = False
    while s and E.get_text_width(s, size) > maxw:
        s, cut = s[:-1], True
    return s + (".." if cut else "")


def hhmm(ms):
    return time.strftime("%H:%M", time.localtime(ms / 1000.0))


def format_name(name, max_len=8):
    if not name:
        return ""
    if len(name) > max_len:
        return name[:max_len] + ".."
    return name


def wrap_text(d, text, font_size, max_w, max_lines=2):
    """Splits or wraps text into up to max_lines fitting max_w."""
    if not text:
        return []
    words = text.split()
    lines = []
    curr = ""
    for w in words:
        test = (curr + " " + w).strip() if curr else w
        if d.textlength(E.shape(test), font=E.font(font_size)) <= max_w:
            curr = test
        else:
            if curr:
                lines.append(curr)
            curr = w
            if len(lines) == max_lines:
                break
    if curr and len(lines) < max_lines:
        lines.append(curr)

    if lines and d.textlength(E.shape(lines[-1]), font=E.font(font_size)) > max_w:
        lines[-1] = trunc(d, lines[-1], font_size, max_w)
    return lines


def call_screen(r):
    """Full screen incoming call card (250x122)."""
    im, d = E.new("red")
    E.text(d, (SW // 2, 16), "INCOMING CALL", size=14, fill="white", anchor="mm")
    name = r.get("who") or "Unknown"
    E.text(d, (SW // 2, 58), name, size=E.fit(d, name, SW - 16, 50, 48), fill="white", anchor="mm")
    d.rectangle([0, SH - 24, SW - 1, SH - 1], fill=Y)
    E.text(d, (SW // 2, SH - 12), "WhatsApp Call" if r.get("src") == "whatsapp" else "Phone Call",
           size=12, fill="black", anchor="mm")
    return E.to_payload(im)


def inbox_screen(d, x0, y0, w, h, chats, missed, show_text, name_max_len=8):
    d.rectangle([x0, y0, x0 + w - 1, y0 + 15], fill=R)
    E.text(d, (x0 + 4, y0 + 8), "Messages", size=10, fill="white", anchor="lm")
    E.text(d, (x0 + w - 4, y0 + 8), time.strftime("%H:%M"), size=9, fill="white", anchor="rm")

    band = 22 if missed else 0
    top = y0 + 18
    bottom = y0 + h - band - 2
    avail_h = max(20, bottom - top)

    if not chats and not missed:
        E.text(d, (x0 + w // 2, (top + bottom) // 2), "No Messages", size=10, fill="black", anchor="mm")
        return

    num_chats = len(chats)
    if num_chats == 0:
        rows = []
        row_h = avail_h
    elif num_chats == 1:
        rows = chats
        row_h = avail_h
    elif num_chats == 2:
        rows = chats
        row_h = avail_h // 2
    else:
        max_rows = max(1, avail_h // 28)
        rows = chats[:max_rows]
        row_h = avail_h // len(rows)

    y = top
    for c in rows:
        formatted_who = format_name(c["who"], name_max_len)
        # Smaller contact name (size 8.5 bold)
        E.text(d, (x0 + 4, y + 2), trunc(d, formatted_who, 8.5, w - 36), size=8.5, fill="black", anchor="lm")
        d.rectangle([x0 + w - 24, y + 1, x0 + w - 4, y + 13], fill=R)
        E.text(d, (x0 + w - 14, y + 7), str(c["count"]), size=8.5, fill="white", anchor="mm")

        # Fluid multi-line message content expansion up to all available space
        if show_text and c.get("last"):
            max_lines = max(1, (row_h - 14) // 10)
            lines = wrap_text(d, c["last"], 8.5, w - 8, max_lines=max_lines)
            line_y = y + 14
            for line in lines:
                E.text(d, (x0 + 4, line_y), line, size=8.5, fill="black", anchor="lm")
                line_y += 10

        y += row_h

    if len(chats) > len(rows):
        E.text(d, (x0 + 4, y - 10), "+%d more" % (len(chats) - len(rows)), size=8.5, fill="red", anchor="lm")

    if missed:
        d.rectangle([x0, y0 + h - band, x0 + w - 1, y0 + h - 1], fill=Y)
        s = "Missed: " + ", ".join("%s %s" % (format_name(m["who"], name_max_len), hhmm(m["ts"])) for m in missed[:2])
        E.text(d, (x0 + 4, y0 + h - band // 2), trunc(d, s, 8.5, w - 8), size=8.5, fill="black", anchor="lm")


def render():
    ib, cfg = load("inbox.json", {}), load("config.json", {})
    limit = time.time() * 1000 - cfg.get("missed_hours", 12) * 3600 * 1000

    show_calls = cfg.get("show_calls", True)
    show_messages = cfg.get("show_messages", True)
    show_missed = cfg.get("show_missed", True)
    compact_weather = cfg.get("compact_weather", True)
    name_max_len = cfg.get("name_max_len", 8)

    ringing = ib.get("ringing") if show_calls else None

    # Expire old chats after chat_timeout_m minutes
    chat_limit = time.time() * 1000 - cfg.get("chat_timeout_m", 30) * 60 * 1000
    raw_chats = ib.get("chats", []) if show_messages else []
    chats = [c for c in raw_chats if c.get("ts", 0) >= chat_limit]

    missed = [m for m in ib.get("missed", []) if m["ts"] >= limit] if show_missed else []

    # Deduplicate chats by contact name
    unique_chats = []
    seen = set()
    for c in chats:
        who = c.get("who")
        if who and who not in seen:
            seen.add(who)
            unique_chats.append(c)

    # 1. Full Screen Incoming Call
    if ringing:
        return call_screen(ringing)

    # 2. No messages / calls -> Full Weather Screen
    if not unique_chats and not missed:
        return weather.render()

    # 3. Messages / Missed Calls
    im, d = E.new()

    if compact_weather:
        try:
            w_data = weather.get(weather.URL)
        except Exception:
            w_data = None

        d.line([(146, 0), (146, SH - 1)], fill=K)
        weather.draw_compact(d, 147, 0, 103, SH, w_data)
        inbox_screen(d, 0, 0, 146, SH, unique_chats, missed, cfg.get("show_text", True), name_max_len)
    else:
        inbox_screen(d, 0, 0, SW, SH, unique_chats, missed, cfg.get("show_text", True), name_max_len)

    return E.to_payload(im)
