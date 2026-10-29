# Mobile image generators

Both scripts build their images from `docs/assets/favicon.svg`, so the app
uses the official logo asset. Re-run them whenever the logo (or, for the splash, the app name) changes,
then commit the regenerated images.

Requires Python 3 with `pymupdf` (renders the SVG) and `pillow`:

    pip install pymupdf pillow

Run from `motionspeak-mobile/`:

| Script | Writes | What it is |
|---|---|---|
| `make_launcher_icons.py` | `app/src/main/res/mipmap-*/ic_launcher*.webp` | Adaptive-icon foreground (all densities) plus legacy square/round icons for API 24-25. The adaptive background is the solid colour in `drawable/ic_launcher_background.xml`. |
| `make_splash_icon.py` | `app/src/main/res/drawable-xxxhdpi/splash_icon.webp` | Logo with "MotionSpeak" under it as one image, fitted to the system splash's 192dp visible circle. Used by the system splash and `SplashActivity`. |

    python tools/make_launcher_icons.py ../docs/assets/favicon.svg app/src/main/res
    python tools/make_splash_icon.py ../docs/assets/favicon.svg \
        app/src/main/res/font/poppins_semibold.ttf \
        app/src/main/res/drawable-xxxhdpi/splash_icon.webp

Both scripts assume the SVG's first path is the blue logo tile
(`fill="rgb(26,56,126)"`) and stop with an error if it is not found. If that
colour changes, update `BLUE` in the scripts and `ms_logo_blue` /
`ic_launcher_background.xml` to match.
