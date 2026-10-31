## Summary
What does this PR change and why? Reference the issue/audit finding (e.g. P0.1) if applicable.

## Checklist
- [ ] `./gradlew compileDebugKotlin` passes
- [ ] `./gradlew testDebugUnitTest` passes (new tests added for new behavior)
- [ ] Every user-facing string added to **both** `values/strings.xml` and `values-ru/strings.xml`
- [ ] Non-root VPN mode **and** root TProxy mode still work
- [ ] Security defaults untouched (allow-lan false, 127.0.0.1 binds, allowBackup false)
- [ ] Core integration constants changed only in `CoreConstants.kt` / `gradle/mihomo-core.properties`
- [ ] Converter changes surface warnings via `convertToMihomoYamlWithOutcome` (no silent losses)

## Screenshots / logs
If UI-visible, attach before/after.
