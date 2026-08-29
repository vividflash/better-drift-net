/*
 * Copyright (c) 2026, vividflash
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.vividflash.betterdriftnet;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("betterdriftnet")
public interface BetterDriftNetConfig extends Config
{
    @ConfigSection(
        name = "Interface",
        description = "Catch interface guards.",
        position = 0,
        closedByDefault = true
    )
    String interfaceSection = "interfaceSection";

    @ConfigSection(
        name = "Nets",
        description = "Harvest gate and fish shoals.",
        position = 1,
        closedByDefault = true
    )
    String netsSection = "netsSection";

    @ConfigItem(
        keyName = "hideTaggedFish",
        name = "Hide tagged fish",
        description = "A tagged shoal is not drawn and has no menu entries until its tag expires.",
        section = netsSection,
        position = 2
    )
    default boolean hideTaggedFish()
    {
        return true;
    }

    @ConfigItem(
        keyName = "taggedFishMarker",
        name = "Tagged fish marker",
        description = "What to draw where a hidden shoal is.",
        section = netsSection,
        position = 3
    )
    default TaggedFishMarker taggedFishMarker()
    {
        return TaggedFishMarker.HULL;
    }

    @Alpha
    @ConfigItem(
        keyName = "taggedFishColour",
        name = "Tagged fish colour",
        description = "Marker colour.",
        section = netsSection,
        position = 4
    )
    default Color taggedFishColour()
    {
        return new Color(255, 255, 255, 120);
    }

    @ConfigItem(
        keyName = "prioritizeUntaggedFish",
        name = "Prioritize untagged fish",
        description = "Where shoals overlap, the untagged one takes left-click.",
        section = netsSection,
        position = 5
    )
    default boolean prioritizeUntaggedFish()
    {
        return false;
    }



    @ConfigItem(
        keyName = "tagTimeoutTicks",
        name = "Tag lasts",
        description = "How long a prodded shoal counts as tagged. Should match how long it stays agitated in game.",
        section = netsSection,
        position = 6
    )
    @Range(min = 10, max = 100)
    @Units(Units.TICKS)
    default int tagTimeoutTicks()
    {
        return 50;
    }

    @ConfigItem(
        keyName = "showNetClickbox",
        name = "Show net clickbox",
        description = "Outlines where each net accepts clicks.",
        section = netsSection,
        position = 7
    )
    default boolean showNetClickbox()
    {
        return false;
    }

    @Alpha
    @ConfigItem(
        keyName = "netClickbox",
        name = "Net clickbox",
        description = "Outline colour.",
        section = netsSection,
        position = 8
    )
    default Color netClickbox()
    {
        return new Color(255, 255, 255, 100);
    }

    @ConfigSection(
        name = "Gear and supplies",
        description = "Trident, numulite and the plant door.",
        position = 2,
        closedByDefault = true
    )
    String gearSection = "gearSection";

    @ConfigItem(
        keyName = "showBlockMessages",
        name = "Show block messages",
        description = "Chat message when a guard blocks a click.",
        position = -1
    )
    default boolean showBlockMessages()
    {
        return true;
    }

    @ConfigItem(
        keyName = "blockClaimOption",
        name = "Block claim option",
        description = "Removes the claim option from the catch interface.",
        section = interfaceSection,
        position = 0
    )
    default boolean blockClaimOption()
    {
        return true;
    }

    @ConfigItem(
        keyName = "claimOptionText",
        name = "Claim option text",
        description = "The option text to remove. Case-insensitive.",
        section = interfaceSection,
        position = 1
    )
    default String claimOptionText()
    {
        return "Take all";
    }

    @ConfigItem(
        keyName = "blockMovingFishOut",
        name = "Block moving fish out",
        description = "Removes 'Move to Inventory' on caught fish until you bank.",
        section = interfaceSection,
        position = 2
    )
    default boolean blockMovingFishOut()
    {
        return true;
    }

    @ConfigItem(
        keyName = "stillMovableItems",
        name = "Still movable items",
        description = "Comma-separated, * wildcards, case-insensitive.",
        section = interfaceSection,
        position = 3
    )
    default String stillMovableItems()
    {
        return "Pufferfish, Numulite, *fossil*, Clue box*";
    }

    @ConfigItem(
        keyName = "bankBeforeClose",
        name = "Bank before close",
        description = "From harvest to the banking prompt, blocks world clicks and the window's X. The bin's destroy prompt lifts it.",
        section = interfaceSection,
        position = 4
    )
    default boolean bankBeforeClose()
    {
        return true;
    }

    @ConfigItem(
        keyName = "blockEarlyHarvest",
        name = "Block early harvest",
        description = "Removes the 'Harvest' option on a net under the threshold. At or above it the entry is left as the game orders it.",
        section = netsSection,
        position = 0
    )
    default boolean blockEarlyHarvest()
    {
        return true;
    }

    @ConfigItem(
        keyName = "minFishToHarvest",
        name = "Min fish to harvest",
        description = "Catch a net needs before this setting stops removing 'Harvest'.",
        section = netsSection,
        position = 1
    )
    @Range(min = 1, max = 10)
    default int minFishToHarvest()
    {
        return 8;
    }

    @ConfigItem(
        keyName = "tridentWarningGuard",
        name = "Trident warning guard",
        description = "Deep-water dialog in the hunting zone: wield-anyway line green, mouse clicks on \"Play it safe.\" blocked. Number keys still select either.",
        section = gearSection,
        position = 0
    )
    default boolean tridentWarningGuard()
    {
        return true;
    }

    @ConfigItem(
        keyName = "highlightUnequippedTrident",
        name = "Highlight unequipped trident",
        description = "Green inventory highlight in the hunting zone with none wielded.",
        section = gearSection,
        position = 1
    )
    default boolean highlightUnequippedTrident()
    {
        return true;
    }

    @ConfigItem(
        keyName = "chasingWeaponNames",
        name = "Chasing weapon names",
        description = "Comma-separated, * wildcards, case-insensitive.",
        section = gearSection,
        position = 2
    )
    default String chasingWeaponNames()
    {
        return "*trident*, *harpoon*";
    }

    @ConfigItem(
        keyName = "markLowNumulite",
        name = "Mark low numulite",
        description = "Red inventory mark in the hunting zone under 5 numulite.",
        section = gearSection,
        position = 3
    )
    default boolean markLowNumulite()
    {
        return true;
    }

    @ConfigItem(
        keyName = "tunnelDialogGuard",
        name = "Tunnel dialog guard",
        description = "Already-paid dialog: 'Enter instance.' green, mouse clicks on 'Don't enter.' blocked. Number keys still select either.",
        section = gearSection,
        position = 4
    )
    default boolean tunnelDialogGuard()
    {
        return true;
    }

    @ConfigItem(
        keyName = "deprioDoorWhileArmed",
        name = "Deprio door while armed",
        description = "Plant door: 'Navigate' and 'Examine' below 'Walk here' while your weapon slot is filled.",
        section = gearSection,
        position = 5
    )
    default boolean deprioDoorWhileArmed()
    {
        return true;
    }
}
