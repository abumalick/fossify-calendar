# Fork notes

Personal build of [Fossify Calendar](https://github.com/FossifyOrg/Calendar). `main` is upstream plus a few commits on top.

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
