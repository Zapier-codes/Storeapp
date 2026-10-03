# Releasing Storeapp

A push to `main` releases the app by itself, but only when something the app is built from changed.
The workflow is `.github/workflows/release-aab.yml`; this page is the operator's view of it.

## What happens on a push

| Stage | What it does | Stops the run when |
|---|---|---|
| **plan** | Works out the version, diffs the commit against the last GitHub Release, asks Zealot whether it already holds that version | nothing the app is built from changed, the commit is already released, or Zealot has the version |
| **build** | Builds the unsigned `.aab`, reads its manifest back and checks it carries the planned application id, versionName and versionCode | any build error, or a bundle that disagrees with the plan |
| **publish** | Uploads the bundle to Zealot with the per-app token, **held** (`hold=true`); expects `201` | any other answer |
| **record** | Creates tag `vX.Y.Z` and a GitHub Release with the bundle attached. The only job that can write to the repository | the release already exists |

"Built from" means `app/` (except `app/src/test` and `app/src/androidTest`), `build.gradle.kts`,
`settings.gradle.kts`, `gradle.properties`, `gradle/`, `gradlew`, `gradlew.bat` and `version.properties`.
Docs, specs, the `listing/` folder, patches and workflows never cut a release. The same list is in the
workflow's `on.push.paths` and in the plan job; change both together.

## Versions

- `versionName` = `major_minor` from `version.properties`, then `.` and the number of commits since that file last changed. `1.1` then `1.1.0`, `1.1.1`, ...
- `versionCode` = commits on the branch + `VERSION_CODE_OFFSET` (repository variable, default `100`).
- The same commit always gets the same pair, so a re-run is recognised and never uploads twice.
- To start a new line (`1.2`, `2.0`), edit `version.properties` and push; that push is itself a release.
- Do not rewrite `main`'s history: it would lower `versionCode`. Raise `VERSION_CODE_OFFSET` if Zealot ever holds a higher code.
- The upstream tags `v1.0.0` to `v1.0.7` are inherited history; `major_minor` stays above them.

## One-time setup

| What | Where | Set by |
|---|---|---|
| Secret `ZEALOT_APP_TOKEN`, variables `ZEALOT_URL`, `ZEALOT_CHANNEL_KEY` | repository secrets and variables | `bin/bootstrap-publishing --only app` (Zealot) |
| Variable `AUTO_RELEASE` = `true` | `gh variable set AUTO_RELEASE -b true -R Zapier-codes/Storeapp` | you, **after one manual run has uploaded correctly** |

Until `AUTO_RELEASE` is `true`, a push builds and checks the bundle but uploads nothing. Set it back to
anything else to pause publishing; builds still run.

## By hand

Actions -> Release AAB -> Run workflow. `dry_run` (default on) plans, builds and checks without uploading;
switch it off to upload from `main`. `force` builds even when nothing relevant changed. From the command line:

```
gh workflow run release-aab.yml -R Zapier-codes/Storeapp                          # dry run
gh workflow run release-aab.yml -R Zapier-codes/Storeapp -f dry_run=false         # upload from main
```

## A release is held until you release it

The upload creates the release **held**: it is not in the catalog index. Zealot compiles and signs the APKs
afterwards; check the release page shows them (and `signed`) before releasing. Then, with the release id from
the run summary and the app token:

```
curl -X POST -H "Authorization: Bearer $(cat ~/storeapp-zealot.token)" \
  https://zealot-deploy-latest.onrender.com/api/releases/<release id>/release
```

Releasing automatically, after waiting for the compile, is leaf `7.a.x.zo`; it needs Zealot to report the
compile state first (Zealot leaves 30 and 31).

## When it fails

Read the log, not the status: `bash ~/D-Store/scripts/fetch-ci-log.sh gh Zapier-codes/Storeapp <run id>`, then upload
`~/storage/downloads/ci-failed-<run id>.log`. If the upload succeeded but **record** failed, the release exists in
Zealot (held) without a tag; create the GitHub Release by hand with that tag, or the next push will upload a newer
version.
