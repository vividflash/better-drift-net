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
package com.vividflash.betterdriftnet.features;

import com.vividflash.betterdriftnet.BetterDriftNetConfig;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Highlights a trident in the inventory. Registered by {@link DriftNetFeature}.
 */
@Singleton
public class DriftNetTridentOverlay extends WidgetItemOverlay
{
    private static final Color FILL = new Color(13, 122, 13, 70);
    private static final Color OUTLINE = new Color(13, 122, 13, 200);

    private final Client client;
    private final BetterDriftNetConfig config;
    private final ItemManager itemManager;

    // Rebuilt only when the chasing-weapon config text changes.
    private String tridentListSource;
    private List<Pattern> tridentPatterns = List.of();

    @Inject
    DriftNetTridentOverlay(Client client, BetterDriftNetConfig config, ItemManager itemManager)
    {
        this.client = client;
        this.config = config;
        this.itemManager = itemManager;
        showOnInventory();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
    {
        if (!config.highlightUnequippedTrident() || !DriftNetFeature.inDriftNetFishingZone(client)
            || !isTrident(itemId) || tridentEquipped())
        {
            return;
        }

        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null)
        {
            return;
        }
        graphics.setColor(FILL);
        graphics.fill(bounds);
        graphics.setColor(OUTLINE);
        graphics.draw(bounds);
    }

    private boolean isTrident(int itemId)
    {
        return matchesList(itemManager.getItemComposition(itemId).getName());
    }

    private boolean tridentEquipped()
    {
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        if (worn == null)
        {
            return false;
        }
        Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        return weapon != null && matchesList(itemManager.getItemComposition(weapon.getId()).getName());
    }

    private boolean matchesList(String itemName)
    {
        String name = itemName.toLowerCase(Locale.ROOT);
        for (Pattern pattern : tridentPatterns())
        {
            if (pattern.matcher(name).matches())
            {
                return true;
            }
        }
        return false;
    }

    private List<Pattern> tridentPatterns()
    {
        String csv = config.chasingWeaponNames();
        if (!csv.equals(tridentListSource))
        {
            tridentListSource = csv;
            tridentPatterns = DriftNetFeature.parseGlobList(csv);
        }
        return tridentPatterns;
    }
}
