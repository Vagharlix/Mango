# Meals, grocery and pantry

How the meal planner chooses recipes, shortens the shopping list and plans around a whole chicken.

## Planner

* **Score** = PantryMatch·40 + ExpiringUsed·30 + InSeason·20 + MacroProximity·10. Each term is normalised to 0–1. A small deterministic tie-breaker under 1 point rotates recipes that score the same.
* **Dislikes and allergies** use whole-word matching on ingredient names and allergen groups: `pork`, `nuts`, `dairy`, `gluten` and `shellfish` all work, and `peas` does not match `peanut butter`.
* **Anti-repetition:** a recipe is not repeated within ±3 days of a cooked or planned use.
* **Portions** scale each recipe to the meal's share of the day's energy (25 / 30 / 30 / 15 %).
* **Grocery-day review:** the Grocery tab shows the 7-day menu with three actions: *Swap*, *Don't like* (hides the recipe) and *Dislike an ingredient*. Each one re-plans the affected meals, and the shopping list updates live. Ticking a shopping-list item adds it to the pantry.
* **Cooked:** deducts the ingredients from the pantry, first-expiry-first-out.
* **Skip / dining out:** no macro compensation and no pantry deduction. The next 3 days are re-planned with a produce boost: the expiry window grows from 3 to 5 days, expiring produce counts double and the expiry weight doubles. The unused food stays in the pantry, so the next shopping list gets shorter on its own.
* **Recipe editor:** a 4-step flow (Basics → Ingredients → Method → Review) with live macro totals. You can swap or remove ingredients. Edited recipes are marked `isUserModified`, new ones `isCustom`.

## Simpler weeks

* **Batch cooking:** 38 recipes that keep well (curries, stews, sauces, soups, grain salads) are cooked once and cover 2–3 main meals within the next 2 days (USDA: cooked leftovers keep 3–4 days in the fridge). Cooking the batch deducts the ingredients for every meal; leftover meals show "From Mon dinner – just reheat". Fish and seafood are always cooked fresh.
* **Easy recipes first:** the planner scores hands-on minutes *per meal*, so a 45-minute curry eaten three times counts as 15 minutes.
* **Breakfast rotation:** 2 different breakfasts a week by default, alternating; change it in Options → Food preferences.
* **Fewer items to buy:** recipes that reuse the week's ingredients are favoured. Interchangeable foods (canned beans, berries, nuts, topping seeds, spinach/kale, lemon/lime) are reduced to one per week, preferring what's in the pantry and never a disliked food. A typical week drops from ~55 to ~36 distinct items.
* **Spice rack:** spices, oils, sauces and stock are listed under Pantry → Spice rack. You keep them stocked yourself, so they never appear on shopping lists. Untick any you'd rather buy weekly.

## Recipe book

* **Classics:** 20 French & Italian dishes (Felicity Cloake's Guardian column, Accademia Italiana della Cucina via GialloZafferano, Marmiton) and 27 Japanese, Chinese and Southeast Asian dishes (Just One Cookbook, The Woks of Life, Fuchsia Dunlop, Hot Thai Kitchen, Andrea Nguyen, RecipeTin Eats, Kawaling Pinoy). Each is written for one adult's portions and shows "Based on" with a link to look up the original.
* **Removing recipes:** tap the remove button on any card (Undo right away); removed recipes are never planned and can be put back from the "Removed" filter.

## Whole chicken

Options → Kitchen: *Off / When it fits / Always*, the usual bird size (1.2–2.0 kg) and the appliances you have (pressure cooker, deep fryer, air fryer).

* **Planning:** a bird not bought yet counts as limited stock (2 breast fillets, 2 thighs, 2 drumsticks, 2 wings, 1 frame). Its pieces pull recipes that use them, and recipes needing more than what's left are penalised, so the week matches one bird. "When it fits" keeps the bird only if the week uses at least half its meat. Both strategies come out of the same scoring: pieces spread over several dishes, or the whole jointed bird in one pot (basquaise, cacciatore, adobo, ayam goreng: 4 meals, capped to what one bird serves at your portion).
* **Shopping:** chicken parts become "1 whole chicken (~1.6 kg)", plus the odd extra piece if a dish needs more. Pieces are counted, not weighed: a thigh is a thigh.
* **Bought:** pieces eaten today or tomorrow go in the fridge (raw poultry keeps 1–2 days), the rest straight into the freezer; a *Kitchen prep* card in Meals shows the 10-piece breakdown, then turns the frame into ~800 ml of stock (pressure cooker or stove method), reminds you to thaw what tomorrow needs and to freeze what would otherwise go off.
* **Homemade stock** stands in for chicken broth (used first); frozen pieces and stock are used up in the following weeks.

## Barcodes

ML Kit (bundled model, works offline) reads EAN-13/8 and UPC-A/E barcodes. The lookup order is:

1. The local barcode cache.
2. If online, Open Food Facts.
3. If neither finds it, you pick the matching food from the local catalog.

Each answer is cached, so a second scan of the same product works offline. The seed catalog holds generic foods rather than real UPCs, because UPCs are product-specific and can't be pre-populated honestly. The catalog grows as you scan.
