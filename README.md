# Better Drift Net

Accidentally misclicked and trashed your catch again?\
Filled up your inventory with fish you now have to drop?\
No more with Better Drift Net.

## Features

Blocked clicks report in chat unless you turn off **Show block messages**;
removed menu options are silent.

**Interface**

- **Bank before close**: between harvesting and the banking confirmation,
  blocks clicks on the game world and on the window's X. The bin's destroy
  confirmation lifts the block.
- **Block claim option**: removes `Take all` from the catch interface. The
  text to match is set in **Claim option text**.
- **Block moving fish out**: removes `Move to Inventory` on caught fish until
  you bank. **Still movable items** lists the exceptions, default
  `Pufferfish, Numulite, *fossil*, Clue bottle*`.

**Nets**

- **Block early harvest**: removes `Harvest` on a net holding fewer fish than
  **Min fish to harvest**, default `8`.
- **Prioritize untagged fish**: where shoals overlap, the untagged one takes
  left-click.
- **Show net clickbox**: outlines where each net accepts clicks, in the
  **Net clickbox** colour.
- **Tagged fish hiding**: a shoal you have prodded is not drawn and has no
  menu entries until its tag expires. **Tag lasts** sets how long that is,
  default `50` ticks.
- **Tagged fish marking**: marks where a hidden shoal is, `Off`, `Hull` or
  `Tile`, in the **Tagged fish colour**.

**Gear and supplies**

- **Deprio door while armed**: moves the plant door's `Navigate` and `Examine`
  below `Walk here` while your weapon slot is filled.
- **Highlight unequipped trident**: green inventory highlight on a chasing
  weapon while you are in the hunting zone with none wielded.
  **Chasing weapon names** sets which items count, default
  `*trident*, *harpoon*`.
- **Mark low numulite**: red inventory mark in the hunting zone while your
  stack is under 5.
- **Trident warning guard**: on the deep-water dialog, highlights the
  wield-anyway line green and blocks `Play it safe.`, by mouse and number key.
- **Tunnel dialog guard**: on the tunnel's already-paid dialog, highlights
  `Enter instance.` green and blocks `Don't enter.`, by mouse and number key.
