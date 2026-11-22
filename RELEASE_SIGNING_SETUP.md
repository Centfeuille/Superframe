# Permanent Android release signing

Superframe keeps the same Android application ID (`com.example.inbetween`) and signs every distributable release APK with one permanent private key. Android requires both the application ID and signing certificate to match when installing an update over an existing app.

## 1. Create the key once

On a Windows computer with JDK `keytool` available on `PATH`, open PowerShell in the repository root and run:

```powershell
.\scripts\prepare-release-signing.ps1
```

The script creates:

- `superframe-release.jks` — the permanent private signing key.
- `superframe-keystore-base64.txt` — its Base64 representation for GitHub Actions.

When `keytool` prompts for passwords, use the same strong password for the keystore and key. Use the alias `superframe` (the script supplies it).

### Linux, macOS or Android (Termux) alternative

If you do not have Windows PowerShell, install a JDK that provides `keytool`, then run these commands from the repository/project working directory. In Termux, install a JDK with `pkg install openjdk-17` first (package availability can vary by Termux repository/version).

```sh
keytool -genkeypair -v -keystore superframe-release.jks -alias superframe -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 superframe-release.jks > superframe-keystore-base64.txt
```

Use the same password for the keystore and key. Keep the `.jks` file and password private and backed up. Copy the complete single-line contents of the Base64 text file into the corresponding GitHub secret. On macOS, use `base64 < superframe-release.jks | tr -d '\n' > superframe-keystore-base64.txt` instead of the Linux/Termux `base64 -w 0` command.

**Back up `superframe-release.jks` and its password in secure offline storage. Never regenerate, replace, commit, or share the key.** If it is lost, future APKs cannot be signed as updates to existing installs.

## 2. Add GitHub Actions repository secrets

Open the GitHub repository, then go to **Settings → Secrets and variables → Actions → New repository secret**. Create these four repository secrets:

| Secret name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Entire contents of `superframe-keystore-base64.txt` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password entered during key creation |
| `ANDROID_KEY_ALIAS` | `superframe` |
| `ANDROID_KEY_PASSWORD` | Key password entered during key creation (same as above if you followed the recommendation) |

Do not add the `.jks`, Base64 text file, or passwords to Git. The `.gitignore` already excludes the generated local files.

## 3. Build and install

Push a commit to `main` / `master`, or run **Actions → Build APK → Run workflow**. Once all four secrets are present, the workflow uploads an artifact named `Superframe-release-apk`, containing `app-release.apk`. The workflow uses its GitHub Actions run number as the Android `versionCode`, so successive CI releases increase monotonically.

If the secrets are missing or incomplete, the workflow uploads a clearly labelled debug fallback instead. A debug fallback is not the release update package and may have a different signing certificate.

## 4. One-time switch from the old APK

The first permanent-key release will not update an existing installation if that installation was signed with a different certificate. Export/backup all important `.inb` / `.inbetween` projects first, then uninstall the old app and install the first `Superframe-release-apk`.

Uninstalling removes app-private data. Once the first permanent-key release is installed, later release APKs signed with the same keystore can be installed over it without uninstalling and without deleting app data.

## Local release builds (optional)

Set these environment variables before running `gradle assembleRelease`:

- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- Optional `ANDROID_VERSION_CODE` (defaults to `2` for local builds)

The GitHub Actions workflow configures these automatically from repository secrets.
