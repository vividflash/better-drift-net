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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Highlights a trident in the inventory. Registered by {@link DriftNetFeature}.
 */
@Slf4j
@Singleton
public class DriftNetTridentOverlay extends WidgetItemOverlay
{
    private static final Color FILL = new Color(13, 122, 13, 70);
    private static final Color OUTLINE = new Color(13, 122, 13, 200);

    private static final byte UNCHECKED = 0;
    private static final byte NOT_TRIDENT = 1;
    private static final byte TRIDENT = 2;

    private final BetterDriftNetConfig config;
    private final Client client;
    private final ItemManager itemManager;
    private final DriftNetFeature feature;

    // Trident verdict indexed by item id, so a lookup does not box the id.
    // Allocated and written by classify, on the client thread; rendering only
    // reads it, and an id without a verdict draws nothing. A clear can arrive
    // off the client thread (config change), so it bumps a counter that
    // classify acts on; while one is pending, rendering draws nothing.
    private byte[] tridentById = new byte[0];
    private volatile int clearRequests;
    private int clearsSeen;

    // Compiled from the chasing-weapon config text by refreshPatterns, which
    // runs on startUp and when that text changes, so rendering never reads config.
    private volatile List<Pattern> tridentPatterns = List.of();

    @Inject
    DriftNetTridentOverlay(BetterDriftNetConfig config, Client client, ItemManager itemManager, DriftNetFeature feature)
    {
        this.config = config;
        this.client = client;
        this.itemManager = itemManager;
        this.feature = feature;
        showOnInventory();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
    {
        if (!feature.isInFishingZone() || !feature.isHighlightUnequippedTrident()
            || clearRequests != clearsSeen || !isTrident(itemId) || tridentEquipped())
        {
            return;
        }

        ItemHighlight.draw(graphics, widgetItem, FILL, OUTLINE);
    }

    /** The cached verdict; an id classify has not seen reads as no trident. */
    private boolean isTrident(int itemId)
    {
        return itemId >= 0 && itemId < tridentById.length && tridentById[itemId] == TRIDENT;
    }

    private boolean tridentEquipped()
    {
        // Cached by the feature from the worn container's changes; -1 is an
        // empty slot. The worn items are classified with the inventory's.
        return isTrident(feature.equippedWeaponId());
    }

    /** Compiles the chasing-weapon list; clearTridentCache follows, as the verdicts are stale. */
    void refreshPatterns()
    {
        tridentPatterns = DriftNetFeature.parseGlobList(config.chasingWeaponNames());
    }

    /** Drops every verdict; nothing is highlighted until the next classify. */
    void clearTridentCache()
    {
        clearRequests++;
    }

    /**
     * Gives a container's items a verdict, on the client thread. The feature
     * calls it for every held item before the highlight can draw: on entering
     * the zone, on a container change there, and when the setting or the
     * weapon list changes.
     */
    void classify(ItemContainer container)
    {
        if (container == null)
        {
            return;
        }
        applyClearRequests();
        for (Item item : container.getItems())
        {
            int itemId = item.getId();
            if (itemId < 0)
            {
                continue;
            }
            if (itemId >= tridentById.length)
            {
                tridentById = Arrays.copyOf(tridentById, Math.max(client.getItemCount(), itemId + 1));
            }
            if (tridentById[itemId] == UNCHECKED)
            {
                boolean trident = DriftNetFeature.matchesAny(tridentPatterns,
                    itemManager.getItemComposition(itemId).getName().toLowerCase(Locale.ROOT));
                tridentById[itemId] = trident ? TRIDENT : NOT_TRIDENT;
                if (trident)
                {
                    log.debug("[trident] item {} is a chasing weapon", itemId);
                }
            }
        }
    }

    private void applyClearRequests()
    {
        int requests = clearRequests;
        if (requests != clearsSeen)
        {
            Arrays.fill(tridentById, UNCHECKED);
            clearsSeen = requests;
        }
    }
}
