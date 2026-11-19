# Training and nutrition

How Mango builds the climbing program and the daily targets, and the research behind each rule.

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
| Max tests | End of every deload week: day 5 tuck front lever hold, max pull-ups, dips and overhead press rep-max (1RM estimated with Epley), max push-ups; day 7 5 s max hang and weighted pull-up 1RM. Week 1 calibrates anything never tested. Results set loads (% 1RM), reps (% of max reps) and holds (% of max hold) from the next day | Lattice Training; Epley (1985); LeSuer et al. (1997) J Strength Cond Res 11:211 |
| Loads | added kg = %1RM × (bodyweight + 1RM added) − bodyweight, rounded to 0.5 kg; negative means pulley assistance | Lattice / Hörst |

## Antagonists

Every training week has push work twice (deficit push-ups / overhead press, dips / pike push-ups depending on the phase), reverse wrist curls, finger extensions with a rubber band, and shoulder health work (band external rotations, YTW, wall slides, scapular push-ups, pec stretch).

## Training equipment

Pick the equipment you can use right now in **Options → Training**, or with the dumbbell button on the Train tab. Changes save straight away and the whole schedule is rebuilt from them. Pick different equipment when you train at home or travel.

* Every exercise in `exercises.json` lists the equipment it `requires`, as groups of alternatives. For example, pull-ups need *pull-up bar or rings*, and weighted pull-ups also need *weights*.
* When something is missing, `Periodization.SUBSTITUTES` swaps in the closest alternative that trains the same thing:
  * max hangs → no-hang edge lifts → limit bouldering
  * weighted pull-ups → tempo pull-ups → band rows → inverted rows
  * overhead press → pike push-ups
  * front lever → dragon flag → hollow body
  * …and so on.
* The app shows which exercise each substitute replaces. Day titles are built from what the session actually contains.
* Hangs that call for added weight when you have no weights say to use a smaller edge instead.
* Test days only include the tests you can do with your equipment.
* Weights are split in two: *weight belt / plates* (weighted pull-ups, dips, hangs) and *dumbbells / barbell* (overhead press and its test, wrist curls). Without dumbbells or a barbell the press becomes pike push-ups. With no equipment at all you still get a full bodyweight and mobility plan.

The protocols (7:3 repeaters, 10 s max hangs, weighted pulls, antagonist push, front-lever and compression core work, loaded mobility, shoulder prehab) and their citations are in `exercises.json`.
