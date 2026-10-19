# Public APK update feeds

These files contain public release metadata only. Việt Hóa PC 1.2.3+ and Emu Game
Store 1.2.5+ fetch the exact `updates/v1/<package>.json` file from this repository's
`main` branch through `raw.githubusercontent.com`.

Publish signed release assets first, verify their digest and size, and then commit
the corresponding feed. Full and Trial have separate package-bound metadata.
Do not replace existing release APKs with different bytes: publish a newer version.
No source from the two private app repositories, credentials or signing keys belongs here.

Account sign-in, activation and Trial enforcement remain on the existing ThorQOL
license service. Android Replay's own update feed and app code are unchanged by
these catalog-app releases. No Netlify deployment is performed by publishing these files.
