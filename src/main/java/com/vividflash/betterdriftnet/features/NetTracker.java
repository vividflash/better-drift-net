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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * The two drift nets: their objects for the clickbox overlay, their catch
 * counts, and the early-harvest gate that reads them. Driven by
 * {@link DriftNetFeature}'s event handlers.
 */
@Slf4j
@Singleton
public class NetTracker
{
    /** The five object-click actions. Their ids are not contiguous. */
    static final Set<MenuAction> OBJECT_CLICKS = EnumSet.of(
        MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
        MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
        MenuAction.GAME_OBJECT_FIFTH_OPTION);

    private static final String HARVEST = "Harvest";
    private static final String DRIFT_NET_TARGET = "drift net";
    static final String EARLY_HARVEST_RULE = "early harvest";

    /** A net holds this many fish, so a catch count outside 0 to 10 is not a real reading. */
    private static final int NET_CAPACITY = 10;

    @Inject
    private Client client;

    @Inject
    private BetterDriftNetConfig config;

    // The two net objects, tracked so the overlay can draw their clickbox.
    private GameObject net1Object;
    private GameObject net2Object;

    // Catch counts of the two nets as of the last game tick, -1 when unread.
    private int net1Catch = -1;
    private int net2Catch = -1;

    // Harvest entry scene coords (packed) of the nearer net, one slot per net:
    // slot 0 is net 1, slot 1 is net 2. Fixed for a scene, so cleared on load.
    private final int[] harvestSceneKeys = {-1, -1};

    // Config values read on per-tick and per-frame paths. Same threading as
    // the feature's cached values.
    private volatile boolean showNetClickbox;
    private volatile Color netClickboxColour;
    private volatile boolean blockEarlyHarvest;
    private volatile int minFishToHarvest;

    void refreshConfig()
    {
        showNetClickbox = config.showNetClickbox();
        netClickboxColour = config.netClickbox();
        blockEarlyHarvest = config.blockEarlyHarvest();
        minFishToHarvest = config.minFishToHarvest();
        log.debug("[config] earlyHarvest={} min={}", blockEarlyHarvest, minFishToHarvest);
    }

    /** Forgets the net objects, the catch counts and the harvest mapping. */
    void clear()
    {
        net1Object = null;
        net2Object = null;
        net1Catch = -1;
        net2Catch = -1;
        clearHarvestSceneCache();
    }

    /**
     * Picks up the nets of a scene that was loaded before the spawn events could
     * be seen. Stops once both nets have been found.
     */
    void scanScene(Scene scene)
    {
        boolean net1Found = false;
        boolean net2Found = false;
        for (Tile[][] plane : scene.getTiles())
        {
            for (Tile[] row : plane)
            {
                for (Tile tile : row)
                {
                    GameObject[] objects = tile == null ? null : tile.getGameObjects();
                    if (objects == null)
                    {
                        continue;
                    }
                    for (GameObject object : objects)
                    {
                        if (object != null)
                        {
                            trackNet(object);
                            net1Found |= object.getId() == ObjectID.FOSSIL_DRIFT_NET1_MULTI;
                            net2Found |= object.getId() == ObjectID.FOSSIL_DRIFT_NET2_MULTI;
                        }
                    }
                    if (net1Found && net2Found)
                    {
                        return;
                    }
                }
            }
        }
    }

    void trackNet(GameObject object)
    {
        int id = object.getId();
        if (id == ObjectID.FOSSIL_DRIFT_NET1_MULTI)
        {
            net1Object = object;
            log.debug("[scene] tracked net1 objectId={}", id);
        }
        else if (id == ObjectID.FOSSIL_DRIFT_NET2_MULTI)
        {
            net2Object = object;
            log.debug("[scene] tracked net2 objectId={}", id);
        }
    }

    void untrackNet(GameObject object)
    {
        if (object == net1Object)
        {
            net1Object = null;
        }
        else if (object == net2Object)
        {
            net2Object = null;
        }
    }

    /** The drift net objects currently in the scene, either may be null. */
    GameObject getNet1()
    {
        return net1Object;
    }

    GameObject getNet2()
    {
        return net2Object;
    }

    boolean isShowNetClickbox()
    {
        return showNetClickbox;
    }

    Color getNetClickboxColour()
    {
        return netClickboxColour;
    }

    boolean isBlockEarlyHarvest()
    {
        return blockEarlyHarvest;
    }

    void onGameTick(boolean inArea)
    {
        // Read only for the early-harvest guard; turned on, it is unread (and
        // so allows the harvest) until the next tick.
        int oldNet1 = net1Catch;
        int oldNet2 = net2Catch;
        if (inArea && blockEarlyHarvest)
        {
            net1Catch = readCatch(VarbitID.FOSSIL_DRIFT_NET1_CATCH);
            net2Catch = readCatch(VarbitID.FOSSIL_DRIFT_NET2_CATCH);
        }
        else
        {
            net1Catch = -1;
            net2Catch = -1;
        }
        logCatchChange(oldNet1, oldNet2);
    }

    void onVarbitChanged(VarbitChanged event, boolean inArea)
    {
        // Also read here, so the Harvest guard sees a new count in the cycle
        // it arrives in rather than at the next game tick.
        if (!inArea || !blockEarlyHarvest)
        {
            return;
        }
        int oldNet1 = net1Catch;
        int oldNet2 = net2Catch;
        if (event.getVarbitId() == VarbitID.FOSSIL_DRIFT_NET1_CATCH)
        {
            net1Catch = readCatch(VarbitID.FOSSIL_DRIFT_NET1_CATCH);
        }
        else if (event.getVarbitId() == VarbitID.FOSSIL_DRIFT_NET2_CATCH)
        {
            net2Catch = readCatch(VarbitID.FOSSIL_DRIFT_NET2_CATCH);
        }
        logCatchChange(oldNet1, oldNet2);
    }

    /** Logs a net's catch count only when it differs from the value before. */
    private void logCatchChange(int oldNet1, int oldNet2)
    {
        if (oldNet1 != net1Catch)
        {
            log.debug("[catch] net1 {} -> {}", oldNet1, net1Catch);
        }
        if (oldNet2 != net2Catch)
        {
            log.debug("[catch] net2 {} -> {}", oldNet2, net2Catch);
        }
    }

    /** A net's catch count, or -1 when the varbit holds no real reading. */
    private int readCatch(int catchVarbit)
    {
        int count = client.getVarbitValue(catchVarbit);
        return count >= 0 && count <= NET_CAPACITY ? count : -1;
    }

    /** The early-harvest rule name when it blocks the entry, or null when it stays. */
    String blockedHarvestRule(MenuEntry entry)
    {
        if (blockEarlyHarvest && isBlockedEarlyHarvest(entry, minFishToHarvest))
        {
            return EARLY_HARVEST_RULE;
        }
        return null;
    }

    /** The early-harvest rule with the counts it read, built only for the debug line. */
    String earlyHarvestDetail()
    {
        return EARLY_HARVEST_RULE + " (net1=" + net1Catch + " net2=" + net2Catch + " min=" + minFishToHarvest + ")";
    }

    /**
     * True for a "Harvest" click on a drift net that holds fewer fish than the
     * configured minimum.
     */
    private boolean isBlockedEarlyHarvest(MenuEntry entry, int minCatch)
    {
        // The object-click check keeps param0/param1 meaningful as scene coords below.
        if (!OBJECT_CLICKS.contains(entry.getType())
            || !HARVEST.equalsIgnoreCase(Text.removeTags(entry.getOption())))
        {
            return false;
        }
        if (!Text.removeTags(entry.getTarget()).toLowerCase(Locale.ROOT).contains(DRIFT_NET_TARGET))
        {
            return false;
        }
        // Only a partial catch is worth guarding. An empty net is left alone: it has
        // nothing to lose, and its menu offers Take down rather than Harvest.
        int count = harvestCatchCount(entry);
        return count >= 1 && count < minCatch;
    }

    /**
     * The catch count of the net a "Harvest" entry refers to, or -1 when it cannot
     * be read. The entry's param0/param1 are the object's scene coords; mapped to a
     * world tile they pick the nearer net's catch varbit. An unreadable count leaves
     * the harvest allowed, so the guard never blocks on a guess. The mapping is
     * kept per scene and the counts are read once per game tick.
     */
    private int harvestCatchCount(MenuEntry entry)
    {
        int catchVarbit = harvestCatchVarbit(entry.getParam0(), entry.getParam1());
        if (catchVarbit == VarbitID.FOSSIL_DRIFT_NET1_CATCH)
        {
            return net1Catch;
        }
        return catchVarbit == VarbitID.FOSSIL_DRIFT_NET2_CATCH ? net2Catch : -1;
    }

    /** The catch varbit of the net nearer the given scene tile, or -1 when it cannot be mapped. */
    private int harvestCatchVarbit(int sceneX, int sceneY)
    {
        int key = (sceneX & 0xFFFF) << 16 | (sceneY & 0xFFFF);
        for (int i = 0; i < harvestSceneKeys.length; i++)
        {
            if (harvestSceneKeys[i] == key)
            {
                // An empty slot holds -1, which maps to no net.
                return key == -1 ? -1 : slotCatchVarbit(i);
            }
        }

        LocalPoint scene = LocalPoint.fromScene(sceneX, sceneY, client.getTopLevelWorldView());
        if (scene == null)
        {
            return -1;
        }

        WorldPoint wp = WorldPoint.fromLocalInstance(client, scene);
        if (wp == null)
        {
            return -1;
        }

        int catchVarbit = DriftNetArea.net1Distance(wp) <= DriftNetArea.net2Distance(wp)
            ? VarbitID.FOSSIL_DRIFT_NET1_CATCH
            : VarbitID.FOSSIL_DRIFT_NET2_CATCH;
        // One slot per net: a key for net 1 goes in slot 0, net 2 in slot 1.
        harvestSceneKeys[catchVarbit == VarbitID.FOSSIL_DRIFT_NET1_CATCH ? 0 : 1] = key;
        return catchVarbit;
    }

    /** The catch varbit of a harvest slot's net. */
    private static int slotCatchVarbit(int slot)
    {
        return slot == 0 ? VarbitID.FOSSIL_DRIFT_NET1_CATCH : VarbitID.FOSSIL_DRIFT_NET2_CATCH;
    }

    private void clearHarvestSceneCache()
    {
        Arrays.fill(harvestSceneKeys, -1);
    }
}
