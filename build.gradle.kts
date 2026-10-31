plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // Audit P2.4: keep the dependency stack honest — `./gradlew dependencyUpdates`
    // reports outdated versions; a scheduled CI job runs it weekly (see
    // .github/workflows/deps-updates.yml). Applied at the root so it aggregates :app.
    alias(libs.plugins.ben.manes.versions)
    // Audit P2.7: static analysis. Config in config/detekt.yml, pre-existing
    // violations frozen in config/detekt-baseline.xml — only new violations fail.
    alias(libs.plugins.detekt)
}
