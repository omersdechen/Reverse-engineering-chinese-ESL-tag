# Edited in-app ("Edit main.py") or over USB. render() must return E.to_payload(image).
import json, time, urllib.request
import esl_lib as E

LAT, LON, CITY = 32.08, 34.78, "Tel Aviv"
WMO = {0: "Clear", 1: "Mostly clear", 2: "Partly cloudy", 3: "Cloudy", 45: "Fog", 48: "Fog",
       51: "Drizzle", 53: "Drizzle", 55: "Drizzle", 61: "Rain", 63: "Rain", 65: "Heavy rain",
       80: "Showers", 81: "Showers", 82: "Showers", 95: "Thunder"}


def fetch():
    url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
           "&current=temperature_2m,weather_code,wind_speed_10m"
           "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1" % (LAT, LON))
    with urllib.request.urlopen(url, timeout=15) as r:
        return json.load(r)


def render():
    j = fetch()
    c, dd = j["current"], j["daily"]
    im, d = E.new()
    E.bar(d, "%s   %s" % (CITY, time.strftime("%d/%m %H:%M")))
    big = "%.0f\u00b0C" % c["temperature_2m"]
    E.text(d, (10, 56), big, E.fit(d, big, 105, 66, 80), "black", "lm")
    E.text(d, (E.SW - 8, 56), WMO.get(c["weather_code"], "Code %d" % c["weather_code"]), 16, "red", "rm")
    d.rectangle([0, E.SH - 24, E.SW - 1, E.SH - 1], fill=E.RGB["yellow"])
    E.text(d, (E.SW // 2, E.SH - 12),
           "Hi %.0f  Lo %.0f  Wind %.0f km/h" % (dd["temperature_2m_max"][0],
                                                   dd["temperature_2m_min"][0], c["wind_speed_10m"]),
           14, "black", "mm")
    return E.to_payload(im)
