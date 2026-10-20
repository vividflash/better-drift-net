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

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Where things are underwater: the area and hunting-zone checks, the net
 * tiles and the entry tiles. The underwater area is an instance, so every
 * point here is in template coords.
 */
final class DriftNetArea
{
    /** Fossil Island underwater area, matching core DriftNetPlugin's region check. */
    private static final int UNDERWATER_REGION = 15008;

    // The nets' adjacent standing tiles from RuneLite core's DriftNetPlugin
    // (template/world coords, plane 1). Net 1 (the FOSSIL_DRIFT_NET1_MULTI
    // object) stands on the row y=10297, x 3746-3749, the northernmost net
    // tile row. Net 2 (the FOSSIL_DRIFT_NET2_MULTI object) stands on the
    // column x=3742, y 10288-10292, the westernmost net tile column. These
    // anchors are the single source of truth for both the hunting-zone box
    // and the harvest-gate net mapping below.
    private static final int NET1_ROW_Y = 10297;
    private static final int NET1_X_MIN = 3746;
    private static final int NET1_X_MAX = 3749;
    private static final int NET2_COL_X = 3742;
    private static final int NET2_Y_MIN = 10288;
    private static final int NET2_Y_MAX = 10292;

    /** The net structures extend 2 tiles beyond their standing tiles. */
    private static final int NET_DEPTH = 2;

    /**
     * Zone boundary offsets into the open water west and north of the nets.
     * Independent of the net anchors above rather than derived from them.
     */
    private static final int HUNTING_WATER_WEST = 3;
    private static final int HUNTING_WATER_NORTH = 2;

    /** Zone margin south/east beyond the nets' outermost standing tiles. */
    private static final int SOUTH_EAST_MARGIN = 3;

    // The drift-net hunting zone, derived from the net anchors above:
    // x 3737-3752, y 10285-10301. The zone check is box-only (no region
    // check): the box (instance-template coords) is unique on the map, and
    // the rest of the underwater area stays vanilla.
    private static final int NET_ZONE_X_MIN = NET2_COL_X - NET_DEPTH - HUNTING_WATER_WEST;   // 3737
    private static final int NET_ZONE_X_MAX = NET1_X_MAX + SOUTH_EAST_MARGIN;                // 3752
    private static final int NET_ZONE_Y_MIN = NET2_Y_MIN - SOUTH_EAST_MARGIN;                // 10285
    private static final int NET_ZONE_Y_MAX = NET1_ROW_Y + NET_DEPTH + HUNTING_WATER_NORTH;  // 10301

    // Per-net adjacent standing tiles are the anchors above (matches core
    // DriftNetPlugin's tile sets). Net 1 -> FOSSIL_DRIFT_NET1_CATCH, net 2 ->
    // FOSSIL_DRIFT_NET2_CATCH. Used to map a "Harvest" object click back to
    // the net it belongs to; the nearest standing tiles of the two nets are 4
    // apart in x and 5 in y, so the nearest-tile pick is unambiguous.
    // Distance ignores plane.

    // The one tile per net a prodded shoal pathfinds to (observed in game
    // 2026-10-17). A shoal on any other net tile may just be swimming past.
    static final WorldPoint NET1_ENTRY = new WorldPoint(NET1_X_MIN, NET1_ROW_Y, 1);       // 3746, 10297
    static final WorldPoint NET2_ENTRY = new WorldPoint(NET2_COL_X, NET2_Y_MIN + 3, 1);   // 3742, 10291

    private DriftNetArea()
    {
    }

    /** Cached once per game tick in inArea for the per-frame paths. */
    static boolean inDriftNetArea(Client client, WorldPoint point)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null || !worldView.isInstance())
        {
            return false;
        }
        return point != null && point.getRegionID() == UNDERWATER_REGION;
    }

    /**
     * The drift-net fishing zone proper. The interface-based guards self-scope
     * via the open window, but the trident dialog appears anywhere underwater,
     * so it needs the tighter box. Cached once per game tick in inFishingZone
     * for the per-frame paths.
     */
    static boolean inDriftNetFishingZone(WorldPoint point)
    {
        return point != null
            && point.getX() >= NET_ZONE_X_MIN && point.getX() <= NET_ZONE_X_MAX
            && point.getY() >= NET_ZONE_Y_MIN && point.getY() <= NET_ZONE_Y_MAX;
    }

    static WorldPoint playerPoint(Client client)
    {
        Player local = client.getLocalPlayer();
        if (local == null)
        {
            return null;
        }
        // The underwater area is instanced: map back to the template region
        // (plain getWorldLocation() returns instance coords that never match).
        return WorldPoint.fromLocalInstance(client, local.getLocalLocation());
    }

    /** Squared distance (plane ignored) from a point to net 1's nearest standing tile. */
    static int net1Distance(WorldPoint from)
    {
        return boxDistance(from, NET1_X_MIN, NET1_X_MAX, NET1_ROW_Y, NET1_ROW_Y);
    }

    /** Squared distance (plane ignored) from a point to net 2's nearest standing tile. */
    static int net2Distance(WorldPoint from)
    {
        return boxDistance(from, NET2_COL_X, NET2_COL_X, NET2_Y_MIN, NET2_Y_MAX);
    }

    /**
     * Squared distance from a point to the nearest tile of an inclusive tile
     * box: the point clamped into the box is that tile.
     */
    private static int boxDistance(WorldPoint from, int xMin, int xMax, int yMin, int yMax)
    {
        int dx = from.getX() - Math.max(xMin, Math.min(from.getX(), xMax));
        int dy = from.getY() - Math.max(yMin, Math.min(from.getY(), yMax));
        return dx * dx + dy * dy;
    }
}
