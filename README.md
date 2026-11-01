# Mango 🥭

Offline-first Android app for climbing training periodization, whole-food nutrition, barcode pantry tracking and zero-food-waste meal planning.
Everything is computed on the device with deterministic Kotlin. There is no AI, no server and no API key. The only network call is an optional Open Food Facts lookup for barcodes the app doesn't know yet.

**Stack:** Kotlin 2.1 · Jetpack Compose + Material 3 (dark theme) · Room (KSP) · Navigation Compose · CameraX + ML Kit (bundled model) · kotlinx.serialization · minSdk 26 / targetSdk 35.

## Project layout

```
app/src/main/
├── assets/                foods.json (173 foods, USDA values) · recipes.json (159 recipes) · exercises.json (48 protocols)
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

### Training equipment

Pick the equipment you can use right now in **Profile**, or tap **Change** on the Training screen. Changes save straight away and the whole schedule is rebuilt from them. Pick different equipment when you train at home or travel.

* Every exercise in `exercises.json` lists the equipment it `requires`, as groups of alternatives. For example, pull-ups need *pull-up bar or rings*, and weighted pull-ups also need *weights*.
* When something is missing, `Periodization.SUBSTITUTES` swaps in the closest alternative that trains the same thing:
  * max hangs → no-hang edge lifts → limit bouldering
  * weighted pull-ups → tempo pull-ups → band rows → inverted rows
  * overhead press → pike push-ups
  * front lever → dragon flag → hollow body
  * …and so on.
* The app shows which exercise each substitute replaces. Day titles are built from what the session actually contains.
* Hangs that call for added weight when you have no weights say to use a smaller edge instead.
* Benchmark day only asks for the tests you can do. With no equipment at all you still get a full bodyweight and mobility plan.

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

### Simpler weeks

* **Batch cooking:** 38 recipes that keep well (curries, stews, sauces, soups, grain salads) are cooked once and cover 2–3 main meals within the next 2 days (USDA: cooked leftovers keep 3–4 days in the fridge). Cooking the batch deducts the ingredients for every meal; leftover meals show "From Mon dinner – just reheat". Fish and seafood are always cooked fresh.
* **Easy recipes first:** the planner scores hands-on minutes *per meal*, so a 45-minute curry eaten three times counts as 15 minutes.
* **Breakfast rotation:** 2 different breakfasts a week by default, alternating; change it in Profile.
* **Fewer items to buy:** recipes that reuse the week's ingredients are favoured. Interchangeable foods (canned beans, berries, nuts, topping seeds, spinach/kale, lemon/lime) are reduced to one per week, preferring what's in the pantry and never a disliked food. A typical week drops from ~55 to ~36 distinct items.
* **Spice rack:** spices, oils, sauces and stock are listed under Pantry → Spice rack. You keep them stocked yourself, so they never appear on shopping lists. Untick any you'd rather buy weekly.

### Recipe book

* **Classics:** 20 French & Italian dishes (Felicity Cloake's Guardian column, Accademia Italiana della Cucina via GialloZafferano, Marmiton) and 27 Japanese, Chinese and Southeast Asian dishes (Just One Cookbook, The Woks of Life, Fuchsia Dunlop, Hot Thai Kitchen, Andrea Nguyen, RecipeTin Eats, Kawaling Pinoy). Each is written for one adult's portions and shows "Based on" with a link to look up the original.
* **Removing recipes:** tap ⊖ on any card (Undo right away); removed recipes are never planned and can be put back from the "Removed" filter.

## Backup & restore

Options → Backup & restore writes everything the user created or changed to a JSON file they choose (profile and options, custom/edited/removed recipes, own foods, pantry, barcodes, benchmarks, meal plan). Bundled data isn't included. Ids are cross-referenced by name so a backup restores on a fresh install. With auto-save on, one chosen file is rewritten a few seconds after every change. Restoring keeps the previous data aside so it can be undone.

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
