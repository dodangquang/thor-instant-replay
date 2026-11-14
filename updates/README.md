# Public APK update feeds

These files contain public release metadata only. Việt Hóa PC 1.2.3+, Emu Game
Store 1.2.5+ and Android Replay 2.9.5+ fetch the exact `updates/v1/<package>.json`
file from this repository's `main` branch through `raw.githubusercontent.com`.

Publish signed release assets first, verify their digest and size, and then commit
the corresponding feed. Full and Trial have separate package-bound metadata.
Do not replace existing release APKs with different bytes: publish a newer version.
No source from the private app repositories, credentials or signing keys belongs here.

Account sign-in, activation and Trial enforcement remain on the existing ThorQOL
license service. No Netlify deployment is performed by publishing these files.

## Replay migration and Netlify cost

Android Replay used to read its manifest from
`https://thorqol-license.netlify.app/updates/v1/<package>.json`. Since 2.9.5 the app
reads it from this repository instead, so an update check costs no Netlify build
minute and no Netlify bandwidth.

Clients older than 2.9.5 still read the Netlify copy, so that copy must stay at
**2.9.5** until those clients have drained. Do not keep publishing new versions to
Netlify: every such release needs a deploy, and 2.9.5 is enough to move a client to
this feed permanently. Once telemetry shows no old clients, the Netlify copies can
be removed; a missing manifest makes old clients ignore updates instead of failing.

The manifest is not the trust anchor: the app still verifies the downloaded APK's
package name, version code, byte length, SHA-256 and signing certificate before it
offers to install anything, so a tampered manifest cannot install a foreign APK.
