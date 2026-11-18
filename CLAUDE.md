# insvMagik: notes for Claude

An Android tool that converts Insta360 `.insv` 360° clips into reframed flat MP4s on the phone.
Read [README.md](README.md), [docs/plan.md](docs/plan.md) (stages, what's next) and
[docs/insv-format.md](docs/insv-format.md) first. Replies in English. Repo `bmesoft/insvMagik`,
**public**, licensed AGPL-3.0 ([LICENSE](LICENSE)): nothing in it may mention or point to private
repos, products' internals or local paths.

## How we work

- **Every change goes through a pull request**: branch from `main` (`feature/…`, `fix/…`), push,
  open a PR (REST: `gh api repos/bmesoft/insvMagik/pulls -f head=… -f base=main -f title=… -f body=…`).
  Merge only when the owner says the PR is good.
- **Build locally**: `.\tools\install.ps1` (build, install over USB, launch); `-ApkOnly` just builds.
  Run `testDebugUnitTest` in `android/` before a PR; with `INSV_SAMPLES` set it also checks the
  real samples. CI (`.github/workflows/build.yml`) runs the tests without samples and builds a
  test APK on every PR and push to main.
- **The owner tests on a real phone.** Say clearly what is unit-tested and what only a phone can
  show. Stage 0's report (Copy button) is how results come back.
- **Format facts go in docs/insv-format.md** with "measured" vs "guess"; new samples are checked
  with `tools/insv-probe.py` first.
- **Never commit samples** (`.insv`, `.lrv`, `.mp4`): size, and they carry serial numbers and maybe GPS.
- **APKs reach people only through releases** (`.github/workflows/release.yml`, signed with the
  release key from the repo secrets; never commit a key, a keystore or its password). Testers
  follow [docs/tester-guide.md](docs/tester-guide.md); keep it in step with the app's buttons.
  Reports go to office@bmesoft.eu. No Insta360 / Antigravity names or logos in
  the app's name or UI beyond saying it reads `.insv`.
- **Licences:** telemetry-parser (MIT/Apache-2.0) may be ported with attribution; no code from
  GPL projects (Gyroflow app).
- Commit messages end with the Co-Authored-By line from the session's system prompt.
