# Development

## Project layout

```
app/src/main/
├── assets/                foods.json (178 foods, USDA values) · recipes.json (163 recipes) · exercises.json (54 protocols and tests)
├── res/raw/               beep_short.wav · beep_long.wav (SoundPool)
└── java/com/mango/
    ├── MangoApp.kt        Application: creates the DB and repository, seeds assets
    ├── MainActivity.kt
    ├── data/              Room entities, DAOs, database, Repository, Open Food Facts client
    ├── domain/            Pure Kotlin engines, no Android imports, unit-tested
    │   ├── Nutrition.kt       Mifflin-St Jeor, TDEE, ISSN/ACSM macro and micronutrient targets
    │   ├── Periodization.kt   Phase allocation, deloads, test days, load calculation, timer intervals
    │   ├── MaxTests.kt        Max tests, Epley 1RM estimates, latest maxes per day
    │   ├── MealPlanner.kt     Dislike filter, recipe scoring, anti-repetition, shopping list, FEFO deduction
    │   ├── WholeChicken.kt    Whole-bird planning, fridge/freezer allocation, kitchen prep tasks
    │   └── FoodPreferences.kt Carb and protein choices, substitutions
    ├── timer/             WorkoutTimerService (foreground service, wake lock, SoundPool, haptics)
    └── ui/                Compose screens, each with its ViewModel in the same file
```

## Build and test

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest   # domain engine tests (app/src/test)
```

Requires JDK 17+ and the Android SDK (platform 35).

The unit tests cover the pure Kotlin engines in `domain/` (planning, nutrition, periodization, max tests, whole chicken) and the backup format. Every push builds a debug APK on GitHub Actions (`.github/workflows/android.yml`).

## Releases

Push a tag such as `v1.0.0` (or run the *Release* workflow from the Actions tab): `.github/workflows/release.yml` runs the tests, builds the release APK signed with the key stored in the repository secrets `MANGO_KEYSTORE_BASE64` and `MANGO_KEYSTORE_PASSWORD`, names it `Mango-<tag>.apk` and publishes it in a GitHub Release with the notes from `.github/release-notes.md`. Keep `versionCode` / `versionName` in `app/build.gradle.kts` in step with the tag.

## README screenshots

`.github/workflows/screenshots.yml` runs `ReadmeScreenshots` (Robolectric, native graphics): the real screens are rendered with demo data and uploaded as an artifact. Run it from the Actions tab after a UI change.

## Backup format

Options → Backup & restore writes everything the user created or changed to a JSON file they choose (profile and options, custom/edited/removed recipes, own foods, pantry, barcodes, benchmarks, meal plan). Bundled data isn't included. Ids are cross-referenced by name so a backup restores on a fresh install. With auto-save on, one chosen file is rewritten a few seconds after every change. Restoring keeps the previous data aside so it can be undone.
