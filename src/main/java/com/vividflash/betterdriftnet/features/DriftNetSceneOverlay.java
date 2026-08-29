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
import com.vividflash.betterdriftnet.TaggedFishMarker;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/** Net clickboxes, and the hull of the tagged shoals {@link DriftNetFeature} hides. */
@Singleton
public class DriftNetSceneOverlay extends Overlay
{
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

        if (config.showNetClickbox())
        {
            for (GameObject net : feature.getNets())
            {
                if (net != null && net.getClickbox() != null)
                {
                    OverlayUtil.renderPolygon(graphics, net.getClickbox(), config.netClickbox());
                }
            }
        }

        TaggedFishMarker marker = config.taggedFishMarker();
        if (!config.hideTaggedFish() || marker == TaggedFishMarker.OFF)
        {
            return null;
        }

        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return null;
        }

        for (NPC npc : worldView.npcs())
        {
            if (!feature.isTaggedShoal(npc))
            {
                continue;
            }
            Shape shape = marker == TaggedFishMarker.TILE
                ? npc.getCanvasTilePoly()
                : npc.getConvexHull();
            if (shape != null)
            {
                OverlayUtil.renderPolygon(graphics, shape, config.taggedFishColour());
            }
        }
        return null;
    }
}
