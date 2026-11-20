# kodi-autobuild

Builds [Kodi](https://github.com/xbmc/xbmc) from an official release tag with one small
EPG guide patch applied, using GitHub Actions. Outputs go to GitHub Releases.

## The patch

`patches/omega-guide-flag.patch` adds ~10 lines to `xbmc/pvr/guilib/GUIEPGGridContainer.cpp`.
Every programme box in the EPG grid gets a per-item property `StartsBeforePage`:
`1` if the programme really starts before the visible page start (it is the clipped first
box of its row, drawn from the grid edge), else `0`. A skin can then align the label with
the programme's real start:

```
String.IsEqual(ListItem.Property(StartsBeforePage),1)
```

`patches/master-guide-flag.patch` is the same change rebased onto newer Kodi code; the
workflow tries the Omega patch first and falls back to this one, so newer tags build too.

## Building

Run the **build-kodi** workflow (Actions tab → build-kodi → Run workflow), give it a Kodi
tag (e.g. `21.3-Omega`) and the platforms (`both`, `windows`, `android`).

- **Windows x64** — built on `windows-2022` with the stock Kodi build scripts
  (`download-dependencies`, `download-msys2`, `make-mingwlibs`, `BuildSetup.bat
  nobinaryaddons noprompt`). Output: a zip of the portable app folder + installer.
- **Android armeabi-v7a** — cross-built on `ubuntu-22.04` with NDK r21e per Kodi's
  `docs/README.Android.md`. Output: a signed APK (signature is constant across builds, so
  a newer APK installs over an older one with `adb install -r`).

Both outputs are checked to actually contain the `StartsBeforePage` property before they
are published, and land on the release `build-<tag>` with SHA-256 lists.

License: the patch is offered under Kodi's license (GPL-2.0-or-later). This repo
distributes no Kodi source; the workflow clones the official repository at build time.
