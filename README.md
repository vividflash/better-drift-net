# Better Drift Net

Guards and highlights for drift net fishing. Blocked clicks report in chat
unless you turn off Show block messages; removed menu options are silent.

## Features

**Interface**

- **Block claim option**: removes the claim option from the catch interface.
- **Claim option text**: the option text to remove, case-insensitive. Default
  `Take all`.
- **Block moving fish out**: removes `Move to Inventory` on caught fish until
  you bank.
- **Still movable items**: exceptions to that block, `*` wildcards. Default
  `Pufferfish, Numulite, *fossil*, Clue bottle*`.
- **Bank before close**: between harvesting and the banking confirmation,
  blocks clicks on the game world and on the window's X. The bin's destroy
  confirmation lifts the block.

**Nets**

- **Block early harvest**: removes the `Harvest` option on a net under the
  threshold. At or above it the entry is left alone, and the game keeps
  `Harvest` off left-click until the net is full.
- **Min fish to harvest**: the threshold, default 8.
- **Hide tagged fish**: a shoal you have prodded is not drawn and has no menu
  entries until its tag expires. The marker below still shows where it is.
- **Tagged fish marker**: what to draw where a hidden shoal is, off, hull or
  tile.
- **Tagged fish colour**: marker colour.
- **Prioritize untagged fish**: where shoals overlap, the untagged one takes
  left-click, so a second click tags the other one.
- **Show net clickbox**: outlines where each net accepts clicks.
- **Net clickbox**: outline colour.
- **Tag lasts**: how long a prodded shoal counts as tagged, default 50 ticks.
  Set it to match how long a shoal stays agitated.

**Gear and supplies**

- **Trident warning guard**: on the deep-water dialog in the hunting zone,
  highlights the wield-anyway line green and blocks mouse clicks on
  "Play it safe.". Number keys still work.
- **Highlight unequipped trident**: green inventory highlight on a chasing
  weapon while you are in the hunting zone with none wielded.
- **Chasing weapon names**: `*` wildcards. Default `*trident*, *harpoon*`.
- **Mark low numulite**: red inventory mark in the hunting zone while your
  stack is under 5.
- **Tunnel dialog guard**: on the tunnel's already-paid dialog, highlights
  `Enter instance.` green and blocks mouse clicks on `Don't enter.`. Number
  keys still work.
- **Deprio door while armed**: moves the plant door's `Navigate` and `Examine`
  below `Walk here` while your weapon slot is filled.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
