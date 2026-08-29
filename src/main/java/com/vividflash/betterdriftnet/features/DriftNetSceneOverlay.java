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
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Scene highlights for the drift-net area, fed by {@link DriftNetFeature}'s
 * object tracking.
 */
@Singleton
public class DriftNetSceneOverlay extends Overlay
{
    private static final Color ANNETTE_HIGHLIGHT = new Color(13, 122, 13);
    private static final Color FULL_NET_HIGHLIGHT = new Color(230, 130, 20);

    private final Client client;
    private final BetterDriftNetConfig config;
    private final DriftNetFeature feature;

    @Inject
    DriftNetSceneOverlay(Client client, BetterDriftNetConfig config, DriftNetFeature feature)
    {
        this.client = client;
        this.config = config;
        this.feature = feature;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!DriftNetFeature.inDriftNetArea(client))
        {
            return null;
        }

        if (config.highlightAnnette() && carriesNoDriftNets())
        {
            outline(graphics, feature.getAnnette(), ANNETTE_HIGHLIGHT);
        }

        if (config.highlightFullNets())
        {
            for (GameObject net : feature.getFullNets())
            {
                outline(graphics, net, FULL_NET_HIGHLIGHT);
            }
        }

        return null;
    }

    private boolean carriesNoDriftNets()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        return inv != null && !inv.contains(ItemID.FOSSIL_DRIFT_NET);
    }

    private static void outline(Graphics2D graphics, GameObject object, Color color)
    {
        if (object == null)
        {
            return;
        }
        Shape clickbox = object.getClickbox();
        if (clickbox != null)
        {
            OverlayUtil.renderPolygon(graphics, clickbox, color);
        }
    }
}
