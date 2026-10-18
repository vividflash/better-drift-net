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

import com.vividflash.betterdriftnet.TaggedFishMarker;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/** Net clickboxes, and the hull of the tagged shoals {@link DriftNetFeature} hides. */
@Singleton
public class DriftNetSceneOverlay extends Overlay
{
    private final DriftNetFeature feature;

    @Inject
    DriftNetSceneOverlay(DriftNetFeature feature)
    {
        this.feature = feature;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!feature.isInArea())
        {
            return null;
        }

        if (feature.isShowNetClickbox())
        {
            Color clickboxColour = feature.getNetClickboxColour();
            renderClickbox(graphics, feature.getNet1(), clickboxColour);
            renderClickbox(graphics, feature.getNet2(), clickboxColour);
        }

        // Collected once per game tick by the feature; the settings are
        // checked here, so a change to them applies on the next frame.
        List<NPC> tagged = feature.getTaggedShoals();
        if (tagged.isEmpty() || !feature.isHideTaggedFish())
        {
            return null;
        }
        TaggedFishMarker marker = feature.getTaggedFishMarker();
        if (marker == TaggedFishMarker.OFF)
        {
            return null;
        }

        Color taggedColour = feature.getTaggedFishColour();
        for (NPC npc : tagged)
        {
            Shape shape = marker == TaggedFishMarker.TILE
                ? npc.getCanvasTilePoly()
                : npc.getConvexHull();
            if (shape != null)
            {
                OverlayUtil.renderPolygon(graphics, shape, taggedColour);
            }
        }
        return null;
    }

    private static void renderClickbox(Graphics2D graphics, GameObject net, Color colour)
    {
        if (net == null)
        {
            return;
        }
        Shape clickbox = net.getClickbox();
        if (clickbox != null)
        {
            OverlayUtil.renderPolygon(graphics, clickbox, colour);
        }
    }
}
