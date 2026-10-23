# ESL Tag app

1. Install Android Studio and Python 3.11 (Windows `py -3.11` launcher; Chaquopy needs it at BUILD time).
2. File > Open > this folder. Let Gradle sync (downloads Chaquopy, Pillow wheel).
3. Phone: enable USB debugging, Run.
4. In the app: allow Bluetooth, tap "Pair tag" (tag must be advertising nearby, vendor app closed),
   tap "Run now" to test. Settings > Apps > ESL Tag > Battery > Unrestricted.
5. Edit `main.py` in the app, or on a PC over USB at
   Android/data/com.omer.esl/files/scripts/ . Optional: drop `font.ttf` there for a custom font.

Colour order is one constant: HW_CODE in esl_lib.py. Upload logs are at the bottom of the main screen.
