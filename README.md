<p align="center">
  <img src="docs/assets/logo.svg" width="128" alt="Mango logo: an orange mango with a green leaf on a dark rounded square">
</p>

<h1 align="center">Mango</h1>

<p align="center">
  <b>Your climbing training, meals and groceries planned for you, on your phone, without internet.</b><br>
  A periodized program that adapts to your tested maxes, a zero-waste meal planner with 163 portion-checked recipes, and one shopping list. No account, no server, no tracking.
</p>

<p align="center">
  <a href="https://github.com/Vagharlix/Mango/releases/latest"><img alt="Latest version" src="https://img.shields.io/github/v/release/Vagharlix/Mango?include_prereleases&label=version&color=FFA62B"></a>
  <a href="https://github.com/Vagharlix/Mango/actions/workflows/android.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/Vagharlix/Mango/android.yml?label=build"></a>
  <a href="#install-in-30-seconds"><img alt="Android 8.0 and newer" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84"></a>
  <a href="#works-offline"><img alt="100% offline" src="https://img.shields.io/badge/100%25-offline-2ea44f"></a>
  <a href="#works-offline"><img alt="No account" src="https://img.shields.io/badge/account-none-222637"></a>
  <a href="docs/training.md"><img alt="Every rule sourced" src="https://img.shields.io/badge/rules-research%20sourced-FFA62B"></a>
</p>

<p align="center">
  <a href="#install-in-30-seconds">Install</a> ·
  <a href="#features">Features</a> ·
  <a href="#your-first-week">First week</a> ·
  <a href="#faq">FAQ</a> ·
  <a href="docs/training.md">Training science</a> ·
  <a href="docs/meals.md">Meal planner</a> ·
  <a href="https://github.com/Vagharlix/Mango/issues">Report a bug</a>
</p>

<p align="center">
  <img src="docs/assets/train.png" width="250" alt="The Train tab: the plan strip, your latest maxes with their progress, and today's session">
  <img src="docs/assets/meals.png" width="250" alt="The Meals tab on grocery day, with the kitchen prep card for a whole chicken">
  <img src="docs/assets/grocery.png" width="250" alt="The shopping list with a whole chicken and what it breaks down into">
</p>

## Install in 30 seconds

1. Download **Mango-vX.Y.Z.apk** from the [latest release](https://github.com/Vagharlix/Mango/releases/latest).
2. Open it on your phone (Android 8.0 or newer). Allow installing unknown apps if your phone asks.
3. Create your profile: age, height, weight, goal. The program and the week's menu are ready right away.

To update, install the new version over the old one: your data stays.

## Features

### Training that follows your real strength

<p align="center">
  <img src="docs/assets/test-day.png" width="320" alt="A test day: tuck front lever hold, max pull-ups, dips and overhead press rep-max, max push-ups, with the Enter test results button">
</p>

- **Periodized program**: Base, Max Strength, Power or Endurance, then Taper, in fixed-length programs or an endless 16-week cycle. Every 4th week is a deload.
- **Test days built in**: the end of every deload week measures your max hang, weighted pull-up, pull-ups, dips, overhead press, push-ups and front lever. The first week calibrates anything you have never tested.
- **Loads that adapt**: from the day after a test, hangboard and pull-up loads are a % of your 1RM, push-ups and pull-ups a share of your max reps, front lever holds a share of your best hold.
- **Antagonists every week**: presses, dips, push-ups, wrist and finger extensors, shoulder health work.
- **Your equipment**: tick what you have (hangboard, wall, rings, weights, dumbbells…). Anything missing is replaced by the closest alternative.
- **Guided timer**: a whole session runs hands-free, with beeps and vibrations, even with the screen off.

### Meals that match your training

<p align="center">
  <img src="docs/assets/recipes.png" width="320" alt="The Boeuf Bourguignon recipe page: quantities for one serving, energy and macros, ingredients">
</p>

- **Daily targets from your phase**: calories, protein (1.6 to 2.2 g/kg), carbs, fat, fibre, vitamin C and omega-3 follow the training block.
- **Zero waste**: recipes are chosen for what's in your pantry and what expires first. Skip a meal and the next days use up its ingredients.
- **Simple weeks**: long recipes are batch cooked for 2 to 4 meals, 2 breakfasts rotate, and interchangeable foods are merged, so a typical week has about a third fewer different items to buy.
- **Never the same dish two days in a row**, and your choices of carbs (rice, pasta, potatoes…) and meats are respected.
- **163 recipes** sized for one person, including French, Italian, Japanese, Chinese and Southeast Asian classics with a link to the original. Remove any recipe in one tap, write your own.

### A smarter shopping list

<p align="center">
  <img src="docs/assets/chicken.gif" width="300" alt="Whole chicken in three steps: the bird on the shopping list, the kitchen prep card telling where each piece goes, and the pieces in the pantry, some frozen">
  <img src="docs/assets/kitchen.png" width="300" alt="Options, Kitchen: whole chicken mode, bird size and the appliances you have">
</p>

- **Whole chicken**: Mango can buy whole birds and plan every piece: breasts, thighs, drumsticks and wings over the week or in one pot, the frame turned into stock, the rest frozen. Pieces are counted, not weighed.
- **Kitchen prep card**: how to break down the bird, when to make stock, what to thaw tonight, what to freeze before it goes off.
- **Barcode pantry**: scan products into the pantry. Unknown barcodes are looked up on Open Food Facts once, then work offline.
- **Spice rack**: spices, sauces and stock you keep stocked never clutter the list.
- **Can't find it?**: pick a replacement in the shop and the week's meals follow.

### Works offline

Everything runs on the phone: the program, the nutrition targets and the meal planner are plain Kotlin code, not AI. No account, no ads, nothing sent anywhere. The only optional internet use is looking up an unknown barcode on Open Food Facts.

Your data stays yours: **Options → Backup & restore** saves it to a file you choose, and can keep that file up to date after every change.

## Your first week

1. **Profile**: enter your body data and goal (bouldering, sport climbing or general strength).
2. **Equipment**: tap the dumbbell button on the Train tab and tick what you can use.
3. **Calibration**: the first week starts with test sessions. Enter your results and every load adapts from the next day.
4. **Grocery day**: review the menu in the Grocery tab, swap what you don't fancy, then tick items as you shop. They go straight into the pantry.
5. **Every day**: tap *Cooked* or *Skip / Dining out* on each meal, and *Start session* on the Train tab.

## FAQ

<details>
<summary><b>My phone blocks the installation</b></summary>

Android asks for permission the first time you install an app outside the Play Store. Allow it for the app you used to open the file (Files, Chrome…), then open the APK again.
</details>

<details>
<summary><b>Android says the app can't be updated</b></summary>

The installed app was signed with a different key (for example a test build). Save a backup in **Options → Backup & restore**, uninstall the old app, install the new one, then choose *Restore a backup* on the welcome screen. Releases are all signed with the same key, so later updates install over each other.
</details>

<details>
<summary><b>Where is my data, and how do I move it to a new phone?</b></summary>

Everything is stored inside the app on your phone. In **Options → Backup & restore**, save a backup file (or turn on auto-save) to a folder such as Downloads. On the new phone, install Mango and choose *Restore a backup* on the welcome screen.
</details>

<details>
<summary><b>I don't have a hangboard / weights / a climbing wall</b></summary>

Untick it in your equipment. Every exercise that needs it is replaced by the closest alternative, and test days only include the tests you can do. With no equipment at all you still get a full bodyweight and mobility plan.
</details>

<details>
<summary><b>Where do the rules and numbers come from?</b></summary>

From sports science and nutrition research: ISSN and ACSM position stands, Mifflin-St Jeor, Lattice Training, USDA FoodData Central and others. Every rule and its source is listed in [docs/training.md](docs/training.md) and [docs/meals.md](docs/meals.md).
</details>

<details>
<summary><b>What about iPhone?</b></summary>

Not available for now.
</details>

## Report a bug or suggest an idea

Open an [issue](https://github.com/Vagharlix/Mango/issues) with a screenshot and your version (shown in **Options → About & sources**).

## Documentation

- [Training and nutrition](docs/training.md): periodization, test days, loads, equipment substitutions, and the source of every rule.
- [Meals, grocery and pantry](docs/meals.md): how recipes are scored, batch cooking, the shopping list, whole chicken, barcodes.
- [Development](docs/development.md): project layout, build and test, backup format.

## Built with AI

Mango was created with the help of AI.

I reviewed every plan and set the direction for how the program works.

The logo is AI-generated. If you are an artist and want to contribute to open source, feel free to make a real logo.

## Credits

- Food data: **[USDA FoodData Central](https://fdc.nal.usda.gov)** and **[Open Food Facts](https://world.openfoodfacts.org)** (ODbL).
- Training methods: **[Lattice Training](https://latticetraining.com)**, Eric Hörst's *Training for Climbing*, Jared Vagy's *Climb Injury-Free*.
- Recipes written after the classics of Felicity Cloake, GialloZafferano, Marmiton, Just One Cookbook, The Woks of Life, Fuchsia Dunlop, Hot Thai Kitchen, Andrea Nguyen, RecipeTin Eats and Andy Cooks.

---

<sub>For personal use. Mango gives general training and nutrition guidance, not medical advice.</sub>
