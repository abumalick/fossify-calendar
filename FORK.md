# Fork notes

Personal build of [Fossify Calendar](https://github.com/FossifyOrg/Calendar). `main` is upstream plus a few commits on top.

## Display time zone

The globe button in the main toolbar switches the whole app between the device's time zone and a chosen one, and back. The first tap, or "Display time zone" in the overflow menu, picks the zone. While another zone is shown, the button has a dot and its label names that zone. Code: `helpers/DisplayTimeZone.kt`, called from `App` and `MainActivity`.

Switching behaves as if the device had changed time zone:

- Synced all-day events are stored as midnight in the default zone, so a switch re-syncs CalDAV calendars and they stay on their dates. The screen ignores touches until that is done. All-day events kept only in this app (birthdays, anniversaries, holidays, imported files, local tasks) are not moved and show one day off while another zone is shown, the same as in upstream when travelling. Editing one of them while another zone is shown saves the shifted date.
- New events take the zone that is shown.
- For a recurring event with a cancelled or moved occurrence within an hour or two of midnight, the wrong occurrence can be hidden or shown after a switch, because exceptions are stored by date and the dates seen in earlier zones are kept.
- If the device's own zone changes while another zone is shown, parts of the app use the new device zone until the main screen is opened again.

## Updating from upstream

```sh
git fetch upstream
git rebase upstream/main
git push --force-with-lease origin main
```

Fork-specific code stays in new files where possible, so rebases rarely conflict.

## Releasing

Push a tag named `v<upstream version>-abu.<n>`, for example `v1.12.1-abu.1`. `.github/workflows/fork-release.yml` then publishes a signed APK as a GitHub release, which Obtainium can track at `https://github.com/abumalick/fossify-calendar`.

The version code is `<upstream VERSION_CODE> * 1000 + <workflow run number>`, so no tracked file changes for a release. Do not rename the workflow file: that resets the run number, and Android refuses updates with a lower version code.

The release build uses the application ID `org.fossify.calendar.debug` so it can sit next to the upstream app. Fossify Commons derives icon alias class names from the application ID and only knows how to strip `.debug`, so any other suffix breaks changing the app icon colour.

Debug builds share that application ID but use a different signing key, so run them and the instrumented tests on an emulator, never on a device that has the release installed. Replacing the release there would mean uninstalling it, which deletes its data.

The upstream workflows do not apply to this fork and are disabled in the repository's Actions settings.

The signing key and its passwords are in Bitwarden under "Fossify Calendar fork release keystore". The app can only be updated in place while it is signed with that key.
