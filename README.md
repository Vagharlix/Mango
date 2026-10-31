# Mango 🥭

Offline-first Android app for climbing training periodization, whole-food nutrition, barcode pantry tracking and zero-food-waste meal planning.
Everything is computed on the device with deterministic Kotlin. There is no AI, no server and no API key. The only network call is an optional Open Food Facts lookup for barcodes the app doesn't know yet.

**Stack:** Kotlin 2.1 · Jetpack Compose + Material 3 (dark theme) · Room (KSP) · Navigation Compose · CameraX + ML Kit (bundled model) · kotlinx.serialization · minSdk 26 / targetSdk 35.

## Project layout

```
app/src/main/
├── assets/                foods.json (128 foods, USDA values) · recipes.json (112 recipes) · exercises.json (39 protocols)
├── res/raw/               beep_short.wav · beep_long.wav (SoundPool)
└── java/com/mango/
    ├── MangoApp.kt        Application: creates the DB and repository, seeds assets
    ├── MainActivity.kt
    ├── data/              Room entities, DAOs, database, Repository, Open Food Facts client
    ├── domain/            Pure Kotlin engines, no Android imports, unit-tested
    │   ├── Nutrition.kt       Mifflin-St Jeor, TDEE, ISSN/ACSM macro and micronutrient targets
    │   ├── Periodization.kt   Phase allocation, deloads, benchmark days, load calculation, timer intervals
    │   └── MealPlanner.kt     Dislike filter, recipe scoring, anti-repetition, shopping list, FEFO deduction
    ├── timer/             WorkoutTimerService (foreground service, wake lock, SoundPool, haptics)
    └── ui/                Compose screens, each with its ViewModel in the same file
```

## Engines and their sources

| Area | Rule | Source |
|---|---|---|
| BMR | Male 10W + 6.25H − 5A + 5 · Female … − 161 | Mifflin et al., *Am J Clin Nutr* 1990 |
| TDEE | BMR × activity factor (1.2 – 1.9) | McArdle, Katch & Katch |
| Protein | 1.6 (Base) → 1.8 (Taper) → 2.0 (Power) → 2.2 (Max Strength) g/kg | ISSN Position Stand (Jäger 2017); Morton 2018 meta-analysis |
| Fat / carbs | Fat 25 % of energy (falls to 20 % if carbs would drop below 3 g/kg), carbs make up the rest | ACSM/AND/DC Joint Position (Thomas 2016) |
| Micronutrients | Fibre 30 g (men) / 25 g (women), vitamin C 90 / 75 mg, ALA omega-3 1.6 / 1.1 g | IOM Dietary Reference Intakes |
| Tendon health | Whole-food protein (glycine, proline) plus vitamin C from seasonal produce, no supplements | — |
| Phases (fixed mode) | 35 % Base · 35 % Max Strength · 20 % Power/Endurance · 10 % Taper, rounded by largest remainder, at least 1 week each | — |
| Evergreen mode | Endless 16-week cycle of four 4-week blocks | — |
| Deload | Every 4th week: −40 % sets, intensity held at week-3 level | Pritchard 2015; Magnusson 2010 |
| Taper | −50 % volume, intensity maintained | Bosquet 2007 meta-analysis |
| Benchmark | Week 4, Day 7 of every block: 5 s max hang and weighted pull-up 1RM; results set the loads for the next block | Lattice Training |
| Loads | added kg = %1RM × (bodyweight + 1RM added) − bodyweight, rounded to 0.5 kg; negative means pulley assistance | Lattice / Hörst |

The protocols (7:3 repeaters, 10 s max hangs, weighted pulls, antagonist push, front-lever and compression core work, loaded mobility, shoulder prehab) and their citations are in `exercises.json`.

## Meal planning

* **Score** = PantryMatch·40 + ExpiringUsed·30 + InSeason·20 + MacroProximity·10. Each term is normalised to 0–1. A small deterministic tie-breaker under 1 point rotates recipes that score the same.
* **Dislikes and allergies** use whole-word matching on ingredient names and allergen groups: `pork`, `nuts`, `dairy`, `gluten` and `shellfish` all work, and `peas` does not match `peanut butter`.
* **Anti-repetition:** a recipe is not repeated within ±3 days of a cooked or planned use.
* **Portions** scale each recipe to the meal's share of the day's energy (25 / 30 / 30 / 15 %).
* **Grocery-day review:** the Grocery tab shows the 7-day menu with three actions: *Swap*, *Don't like* (hides the recipe) and *Dislike an ingredient*. Each one re-plans the affected meals, and the shopping list updates live. Ticking a shopping-list item adds it to the pantry.
* **Cooked:** deducts the ingredients from the pantry, first-expiry-first-out.
* **Skip / dining out:** no macro compensation and no pantry deduction. The next 3 days are re-planned with a produce boost: the expiry window grows from 3 to 5 days, expiring produce counts double and the expiry weight doubles. The unused food stays in the pantry, so the next shopping list gets shorter on its own.
* **Recipe editor:** a 4-step flow (Basics → Ingredients → Method → Review) with live macro totals. You can swap or remove ingredients. Edited recipes are marked `isUserModified`, new ones `isCustom`.

## Barcodes

ML Kit (bundled model, works offline) reads EAN-13/8 and UPC-A/E barcodes. The lookup order is:

1. The local barcode cache.
2. If online, Open Food Facts.
3. If neither finds it, you pick the matching food from the local catalog.

Each answer is cached, so a second scan of the same product works offline. The seed catalog holds generic foods rather than real UPCs, because UPCs are product-specific and can't be pre-populated honestly. The catalog grows as you scan.

## Build and test

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest   # domain engine tests (app/src/test)
```

Requires JDK 17+ and the Android SDK (platform 35).
