# Go3 Air visual identity

The Air emblem combines two swept cyan/blue wings into an A silhouette with a right-facing play shape in the negative space. The wordmark and dark navy airflow backdrop are shared by the launch screen and TV banner. The launcher uses a horizontal lockup with a much larger wordmark for readability at 320 × 180; the splash keeps its centered stacked layout.

Runtime assets:
- `app/src/main/res/drawable-nodpi/app_icon_air.png`: 512 × 512 launcher icon.
- `app/src/main/res/drawable-nodpi/tv_banner_air.png`: 320 × 180 TV banner.
- `app/src/main/res/drawable-nodpi/splash_air.webp`: 1672 × 941 launch artwork (native generated resolution; not upscaled).

Android's window background and Compose startup view both fill the screen with the same splash image. The title is already baked into the artwork; only the connection status is drawn at runtime. No animations or extra runtime libraries were added.

Created with the built-in image generation tool on 2026-09-04. The generated PNGs were resized/encoded with Sharp. Package IDs and signing configuration are unchanged.

## Splash prompt

Use case: logo-brand
Asset type: finished 16:9 Android TV splash and launcher banner for an independent television app called Go3 Air. Create one polished full-bleed image, ideally 3840x2160.
Design direction: quiet premium dark navy (#040b19) background, restrained electric cyan and ice blue. Center a distinctive original compact Air emblem above the wordmark: an open triangular capital A made from two wide elegant swept aerodynamic ribbons, with a subtle right-facing play triangle cut into the negative space. Bold simple silhouette, smooth precise edges, legible at icon size. The symbol feels light, fast, and flowing. Not a stock wifi symbol, no TV hardware or screen illustration.
Exact wordmark under emblem: "Go3 Air" in refined modern rounded geometric sans serif, generous kerning, white Go3 and pale cyan Air, easy to read from a sofa. Custom independent typography, not the official Go3 logo.
Composition: emblem centered at x50%, y38%, occupying about 18% of canvas width and 27% of canvas height; wordmark centered at y61%, occupying about 31% width. Broad generous empty space. Bottom 18% remains dark and free of text for runtime loading status. Very faint abstract airflow bands only in distant lower left and upper right corners, navy and deep blue with a delicate cyan edge. Logo is the clear focus.
No additional words, tagline, badges, device mockup, stars, particle clutter, lens flare, chrome, watermark, border, UI cards. Professionally finished identity rather than sci-fi wallpaper.

## Launcher readability revision prompt

Use case: compositing
Asset type: 16:9 Android TV app launcher tile, production artwork displayed at only 320x180 pixels.
Edit target: attached Go3 Air banner. Keep the exact cyan swept A/play emblem, geometric Go3 Air wordmark style, deep navy background, and subtle corner airflow accents. Recompose it for MUCH greater text readability at thumbnail size.
Layout: horizontal lockup centered vertically. Emblem on the left within x=6–29% and y=26–72%. To its right place the exact wordmark "Go3 Air" on ONE line, x=34–94%, center y=50%, cap height at least 20% of the canvas height. White Go3, pale cyan Air, medium-bold geometric lettering. The text must be about 2.3 times wider and twice as tall as in the supplied image. Use almost the full available tile width with just 6% outer safety margins. Ensure the emblem does not intrude into the lettering.
Do not stack emblem above the name. No extra text, badges, captions, border or mockups. Preserve brand appearance and precise spelling. This must look like a legible application tile, not a splash screen shrunk to a thumbnail.

## Icon prompt

The splash image was supplied as the visual reference.

Use case: compositing
Asset type: square Android TV app icon, high-resolution 1024x1024.
Input image: reference for the exact newly created Go3 Air emblem.
Extract and faithfully reproduce ONLY the cyan aerodynamic A/play emblem from the center of the reference, preserving its exact silhouette, negative-space right-facing play shape, two swept wings, and restrained cyan-to-blue shading. Center it optically on a perfectly plain very dark navy #040b19 square background. Emblem occupies about 62% width and 62% height, generous margins for circular launcher masks. Full bleed square, no rounded outer container, no shadow, no text or wordmark, no background waves, no TV illustration. This is the companion launcher icon for that very same brand; do not invent a different symbol.
