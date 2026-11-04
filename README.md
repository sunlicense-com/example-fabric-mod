# Fabric server mod with SunLicense

A working, copyable [Fabric](https://fabricmc.net) server mod with [SunLicense](https://sunlicense.com)
built in: each server needs a valid license key, leaked keys can be revoked or blocked, and your
customers' servers keep running if SunLicense is ever unreachable.

- Fabric Loom, Fabric API and the official [Java SDK](https://github.com/sunlicense-com/sunlicense-java) nested in the mod jar
- License key from **config/mymod.properties**, or built in by **BuiltByBit / Voxel Shop** placeholders
- Clear console messages for every result (expired, revoked, too many servers, blacklisted, …), keys masked
- Re-checked every few hours, so a revoke reaches running servers (your features switch off)
- **Offline fallback** with a signed license file (Ed25519, verified without network)
- Tests against a stub server, CI on GitHub Actions, and [AGENTS.md](AGENTS.md) for AI coding assistants

For Minecraft 26.3 with Java 25 (`gradle.properties` holds the versions; check
[fabricmc.net/develop](https://fabricmc.net/develop) for newer ones). Server-side only
(`"environment": "server"`): the check runs on the dedicated server, never on players' clients.

## 1. Set up SunLicense

In the [dashboard](https://app.sunlicense.com):

1. **Products → New product** (your mod), then a **policy**, e.g. "Pro", *2 machines*: a machine is a server here.
2. **API keys → Create API key**. It can only validate licenses, so it's safe inside a jar.
3. Optional, for the offline fallback: open any license and copy **Public key**.

## 2. Make it yours

1. Use this template (or copy the folder), then rename `com.example.mymod`, `MyMod`, the mod id `mymod`
   (`fabric.mod.json`, `MyMod.MOD_ID`) and `my-mod` (`settings.gradle`).
2. Put the API key in your **user** Gradle properties (`~/.gradle/gradle.properties`), never in git:

   ```properties
   sunlicenseApiKey=slk_...
   sunlicensePublicKey=MCowBQYDK2VwAyEA...
   ```

   For a local SunLicense stack add `sunlicenseUrl=http://localhost`.
3. Build: `./gradlew build` → `build/libs/my-mod-1.0.0.jar` (Java 25 needed; Gradle downloads itself
   and Minecraft the first time).
4. Try it: `./gradlew runServer` starts a local Fabric server with the mod (the first time, accept
   Minecraft's EULA in `run/eula.txt` and start it again; `stop` stops it). Put a test license key in
   `run/config/mymod.properties` and run `/mymod recheck`.

Fabric can't unload a mod, so register your events and commands as usual and start each one with
`if (!MyMod.active()) return;`. `MyMod.startFeatures()` runs once when the license first becomes valid.

## 3. Sell it

| Where | How the key gets to the server |
|---|---|
| [BuiltByBit](https://docs.sunlicense.com/integrations/builtbybit), [Voxel Shop](https://docs.sunlicense.com/integrations/voxel-shop) | Create a `%%__SUNLICENSE__%%` placeholder in the store. It replaces `BUILT_IN_KEY` in `MyMod` with each buyer's key on download, so there's nothing to configure |
| [Modrinth and CurseForge](https://docs.sunlicense.com/integrations/free-platforms) (free downloads, paid key), [Tebex](https://docs.sunlicense.com/integrations/tebex), [Gumroad](https://docs.sunlicense.com/integrations/gumroad) and the other stores | The buyer gets a key by email or on the redeem page and puts it in `config/mymod.properties` |

## What happens on a server

| SunLicense answers | The mod |
|---|---|
| Valid | `MyMod.active()` turns true and `startFeatures()` runs; logs the masked key |
| Expired, revoked, suspended, unknown key, too many servers, blacklisted | Logs why (see `license/Messages.java`); `active()` stays (or turns) false |
| Nothing (outage, network, rate limit) | Uses `config/mymod-license.lic` if it's a valid offline license for this key; otherwise keeps running and retries every 10 minutes (`ALLOW_WHEN_UNREACHABLE = false` to stay off instead) |

Each server keeps a random id in `config/mymod-server-id`, which is what counts against the policy's
machine limit. `/mymod` shows the license status; `/mymod recheck` re-reads the key and checks again
(operators only).

Developing against your own license? Add an **exclusion** for your machine or IP in SunLicense
([blacklists and exclusions](https://docs.sunlicense.com/guide/access-rules)) so your test servers
don't use up slots.

**Usage statistics.** Every 30 minutes, in the background, the template tells SunLicense its version,
the Minecraft/Java version, OS, architecture and CPU cores, so you see who runs what on the product's
**Analytics** tab (with the license and machine, like bStats). No player names, IP addresses or server
names. Buyers switch it off with `analytics=false` in `config/mymod.properties` or
`SUNLICENSE_ANALYTICS=off`; it never affects the license check. What is sent:
[docs](https://docs.sunlicense.com/guide/sdks#built-in-analytics).

## Protection

Release builds go further than a license check that an AI editor or a cracker could delete:

- **Sealed core.** Everything in the `core` package (`core/CoreFeatures.java`: your features) is encrypted in the
  release jar by `sunlicense seal`. Only a valid license check returns the key that opens it, so a copy whose check
  was cut out has nothing to run. The key from the last valid check is kept for 7 days, encrypted for this
  server (a `.sunlicense-unlock-…` file next to `config/mymod-license.lic`), and an offline license file carries it too, so
  an outage doesn't stop your customers. A suspended, expired or revoked license gets no key.
- **Tamper reports.** The seal carries the SHA-256 of your other classes. A modified jar still starts, but says so
  in its next license check, and [Sentinel](https://docs.sunlicense.com/guide/sentinel) suggests suspending that license.
- **Ownership notice.** The header comment in `MyMod.java` and one start-up line
  (`MyMod is licensed software by You. License AX8P-XXXX-XXXX-XXXX. Terms: …`): set `AUTHOR` and `TERMS`.

Build a release with the [SunLicense CLI](https://docs.sunlicense.com/cli/) signed in (or `SUNLICENSE_TOKEN` set):

```bash
./gradlew build -PsunlicenseProduct=<your product code>
```

Ship `build/release/<jar>`. Without the property, `./gradlew build` is a development build with the core in
plain: test with it, don't sell it. In GitHub Actions, add the secret `SUNLICENSE_TOKEN` (a personal access
token) and the variable `SUNLICENSE_PRODUCT`, and the workflow builds the release jar. Code the platform loads by
name (the main class, mixins and the fabric.mod.json entrypoint) stays outside `core`.

Honest limit: someone with a valid license can still pull the key out of memory and patch their copy; that copy
then reports itself as modified. [How protection works](https://docs.sunlicense.com/guide/protection).

## Files

```
build.gradle, gradle.properties           Loom, Minecraft, Fabric API, SDK, settings baked into the jar
src/main/java/.../MyMod.java              start-up, re-checks, /mymod, active(), your features
src/main/java/.../core/CoreFeatures.java    your features: the core, sealed in release builds
src/main/java/.../license/SealedCore.java  loads the core (sealed or plain) and checks the jar
src/main/java/.../license/SunLicenseProtection.java unlock, cache, integrity, notice (a copy of the SDK's; replace, don't edit)
src/main/java/.../license/LicenseGuard.java   online check → offline file → status (no Minecraft code, tested)
src/main/java/.../license/OfflineLicense.java Ed25519 offline license file check
src/main/java/.../license/Messages.java   what the server owner reads
src/main/java/.../license/SunLicenseAnalytics.java anonymous usage statistics (a copy of the SDK's; replace, don't edit)
src/main/resources/mymod.properties       license-key, recheck-hours, analytics (copied to config/ on first start)
src/test/java/.../LicenseGuardTest.java   every result against a stub SunLicense
src/test/java/.../SealedCoreTest.java     the sealed core opens only with a valid unlock
.github/workflows/build.yml               CI
```

## Licence

[MIT No Attribution](LICENSE): copy any of it into your mod, commercial or not, without credit.
