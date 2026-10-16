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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
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

    /** No weapon id checked since the last cache clear. */
    private static final int NOT_CHECKED = Integer.MIN_VALUE;

    private final BetterDriftNetConfig config;
    private final ItemManager itemManager;
    private final DriftNetFeature feature;

    // Trident verdict indexed by item id, grown on demand, so a lookup does not
    // box the id. Only the client thread touches the array; a clear can arrive
    // off it (config change), so it bumps a counter the lookup then acts on.
    private byte[] tridentById = new byte[0];
    private volatile int clearRequests;
    private int clearsSeen;

    // The weapon id last checked and its verdict, so the equipped check is a
    // field compare while the weapon stays the same.
    private int checkedWeaponId = NOT_CHECKED;
    private boolean weaponIsTrident;

    // Rebuilt only when the chasing-weapon config text changes.
    private String tridentListSource;
    private List<Pattern> tridentPatterns = List.of();

    @Inject
    DriftNetTridentOverlay(BetterDriftNetConfig config, ItemManager itemManager, DriftNetFeature feature)
    {
        this.config = config;
        this.itemManager = itemManager;
        this.feature = feature;
        showOnInventory();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
    {
        if (!feature.isInFishingZone() || !feature.isHighlightUnequippedTrident()
            || tridentEquipped() || !isTrident(itemId))
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
        if (itemId < 0)
        {
            return false;
        }
        int requests = clearRequests;
        if (requests != clearsSeen)
        {
            clearsSeen = requests;
            tridentById = new byte[0];
            checkedWeaponId = NOT_CHECKED;
        }
        if (itemId < tridentById.length && tridentById[itemId] != UNCHECKED)
        {
            return tridentById[itemId] == TRIDENT;
        }

        boolean trident = matchesList(itemManager.getItemComposition(itemId).getName());
        if (itemId >= tridentById.length)
        {
            tridentById = Arrays.copyOf(tridentById, Math.max(itemId + 1, tridentById.length * 2));
        }
        tridentById[itemId] = trident ? TRIDENT : NOT_TRIDENT;
        return trident;
    }

    void clearTridentCache()
    {
        clearRequests++;
    }

    private boolean tridentEquipped()
    {
        // Cached by the feature from the worn container's changes; -1 is an
        // empty slot.
        int weaponId = feature.equippedWeaponId();
        if (weaponId != checkedWeaponId)
        {
            weaponIsTrident = isTrident(weaponId);
            checkedWeaponId = weaponId;
            log.debug("[trident] equipped weapon {} is trident: {}", weaponId, weaponIsTrident);
        }
        return weaponIsTrident;
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
