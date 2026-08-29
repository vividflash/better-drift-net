# Better Drift Net

Guards and highlights for drift net fishing. Blocked clicks report in chat
unless you turn off Show block messages; removed menu options are silent.

## Features

**Interface**

- **Block claim option**: removes the claim option from the catch interface.
- **Claim option text**: case-insensitive. Default `Take all`.
- **Block moving fish out**: removes `Move to Inventory` on caught fish.
- **Still movable items**: exceptions to that block, `*` wildcards. Default
  `Pufferfish, Numulite, *fossil*, Clue box*`.
- **Bank before close**: between harvesting and the banking confirmation,
  blocks clicks on the game world and on the window's X. The bin's destroy
  confirmation lifts the block.

**Nets**

- **Block early harvest**: removes `Harvest` on a net under the threshold
  below.
- **Min fish to harvest**: default 8.
- **Highlight full nets**: orange outline on a full net.
- **Highlight Annette when netless**: green outline while you carry no drift
  nets.

**Gear and supplies**

- **Trident warning guard**: on the deep-water dialog in the hunting zone,
  highlights the wield-anyway line green and blocks mouse clicks on
  "Play it safe.". Number keys still work.
- **Highlight unequipped trident**: green inventory highlight on a chasing
  weapon while you are in the hunting zone with none wielded.
- **Chasing weapon names**: `*` wildcards. Default `*trident*, *harpoon*`.
- **Mark low numulite**: red inventory mark in the hunting zone while your
  stack is under 5.
- **Deprio door while armed**: moves the seaweed door's `Enter` and `Examine`
  below `Walk here` while your weapon slot is filled.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
