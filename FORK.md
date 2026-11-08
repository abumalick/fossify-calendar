# Fork notes

Personal build of [Fossify Calendar](https://github.com/FossifyOrg/Calendar). `main` is upstream plus a few commits on top.

## Display time zone

The globe button in the main toolbar switches the whole app between the device's time zone and the favorite zones listed under "Display time zones" in the settings. With one favorite it toggles between that zone and the device's, with several it opens a list, and with none it asks for one. While another zone is shown, the button has a dot and its label names the device's zone. Code: `helpers/DisplayTimeZone*.kt` and `helpers/AllDayEventZone.kt`, called from `App`, `MainActivity` and `SettingsActivity`.

Switching replaces the app's default time zone:

- Timed events are instants, so they move on their own.
- All-day events are stored as times of day in the default zone, and that zone is recorded on each event. A switch moves every all-day event, and the completed occurrences of all-day tasks, to the same times of day in the new zone, in one database transaction, before the new zone is saved, so if the move fails the app keeps showing the old zone. Events already in the new zone are left alone. The task editor does not record a zone, so tasks are moved from the zone shown before the switch. Nothing is re-synced.
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

Debug builds share that application ID but use a different signing key, so run them and the instrumented tests on a test device that does not have the release installed. Replacing the release there would mean uninstalling it, which deletes its data.

The upstream workflows do not apply to this fork and are disabled in the repository's Actions settings.

The signing key and its passwords are in Bitwarden under "Fossify Calendar fork release keystore". The app can only be updated in place while it is signed with that key.
