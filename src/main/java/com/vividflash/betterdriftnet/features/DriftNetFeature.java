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
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

/**
 * Drift net interface guards and highlights. Most of the feature is scoped to
 * the Fossil Island underwater area (region 15008, the same check RuneLite
 * core's Driftnet plugin uses); the zone-gated parts (trident dialog, and the
 * trident and numulite overlays) are further scoped to the hunting zone box
 * below. The door deprio needs only the area check: the airlock in front of
 * the plant door is part of the instance.
 *
 * <p>The bank-before-close guard is an armed/disarmed state machine: the
 * drift net interface loading (re)arms it, and confirming the bank or a bin
 * destroy disarms it. While armed it consumes the world and window-close
 * clicks that would discard the catch.
 *
 * <p>Sub-features live in three overlay classes: trident equip reminder in
 * {@link DriftNetTridentOverlay}, numulite balance warning in
 * {@link DriftNetNumuliteOverlay}.
 */
@Slf4j
@Singleton
public class DriftNetFeature implements RenderCallback, KeyListener
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

    // The tunnel's already-paid dialog. Both markers are required so the guard
    // cannot fire on some other dialog that happens to offer an instance.
    private static final String ENTER_INSTANCE = "Enter instance.";
    private static final String DONT_ENTER = "Don't enter.";
    private static final String FREE_ACCESS_MARKER = "permanent free access";

    private static final String MOVE_TO_INVENTORY = "Move to Inventory";
    private static final String HARVEST = "Harvest";
    private static final String DRIFT_NET_TARGET = "drift net";

    // Per-net adjacent standing tiles, built from the anchors above (matches
    // core DriftNetPlugin's tile sets). Net 1 -> FOSSIL_DRIFT_NET1_CATCH,
    // net 2 -> FOSSIL_DRIFT_NET2_CATCH. Used to map a "Harvest" object click
    // back to the net it belongs to; the nearest standing tiles of the two
    // nets are 4 apart in x and 5 in y, so the nearest-tile pick is
    // unambiguous. Distance ignores plane.
    private static final WorldPoint[] NET1_TILES = tileRow(NET1_X_MIN, NET1_X_MAX, NET1_ROW_Y);
    private static final WorldPoint[] NET2_TILES = tileColumn(NET2_COL_X, NET2_Y_MIN, NET2_Y_MAX);

    // The one tile per net a prodded shoal pathfinds to (observed in game
    // 2026-10-17). A shoal on any other net tile may just be swimming past.
    private static final WorldPoint NET1_ENTRY = new WorldPoint(NET1_X_MIN, NET1_ROW_Y, 1);       // 3746, 10297
    private static final WorldPoint NET2_ENTRY = new WorldPoint(NET2_COL_X, NET2_Y_MIN + 3, 1);   // 3742, 10291

    // Ticks a tagged shoal keeps its tag after reaching an entry tile. Caught
    // shoals despawned 0 to 3 s after reaching it in game (2026-10-17).
    private static final int ENTRY_GRACE_TICKS = 5;

    // The plant door into the drift-net area, "Navigate" in game. It cannot be
    // swum through with a weapon equipped, hence the while-armed deprioritization.
    // Not one of the FOSSIL_DRIFTNET_ENTRANCE ids, which are the paid-access tunnel.
    private static final int DOOR_ID = ObjectID.FOSSIL_UNDERWATER_DRIFTNET_CURTAIN;

    /** The five object-click actions. Their ids are not contiguous. */
    private static final Set<MenuAction> OBJECT_CLICKS = EnumSet.of(
        MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
        MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
        MenuAction.GAME_OBJECT_FIFTH_OPTION);

    /** The entry kinds the plant door can contribute: its options plus Examine. */
    private static final Set<MenuAction> DOOR_CLICKS = doorClicks();

    /** One drift-net banking fee, in numulite. */
    static final int BANKING_FEE = 5;

    /** A net holds this many fish, so a catch count outside 0 to 10 is not a real reading. */
    private static final int NET_CAPACITY = 10;

    // Config keys whose change refreshes the tagged shoal list at once.
    private static final String HIDE_TAGGED_KEY = "hideTaggedFish";
    private static final String MARKER_KEY = "taggedFishMarker";

    /** Weapon slot item id when the worn container has not been read yet. */
    private static final int WEAPON_UNKNOWN = -2;

    private static final int SHOAL_UNTAGGED = 1;
    private static final int SHOAL_TAGGED = 2;

    // A prod is what tags a shoal, and nothing announces the tag ending, so tags are
    // held per NPC index and end on a timer, on despawn, or on reaching a net. The shoal's own
    // animation is no use here: it tracks whether the fish is moving, not whether it
    // has been prodded.
    private static final String PROD_MESSAGE = "prod at the shoal";

    // Deep-water trident warning dialog (e.g. wielding the Merfolk trident in
    // the area): "Play it safe." cancels the wield; the other line keeps it.
    private static final String PLAY_IT_SAFE = "Play it safe.";
    private static final String WIELD_ANYWAY_MARKER = "wield this anyway";
    private static final int HIGHLIGHT_GREEN = 0x0d7a0d;

    /** Scene clicks that close the drift net interface and dump the catch. */
    private static final Set<MenuAction> SCENE_CLICKS = EnumSet.of(
        MenuAction.WALK,
        MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
        MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
        MenuAction.GAME_OBJECT_FIFTH_OPTION,
        MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
        MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION,
        MenuAction.NPC_FIFTH_OPTION,
        MenuAction.GROUND_ITEM_FIRST_OPTION, MenuAction.GROUND_ITEM_SECOND_OPTION,
        MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
        MenuAction.GROUND_ITEM_FIFTH_OPTION,
        MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
        MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION,
        MenuAction.PLAYER_FIFTH_OPTION, MenuAction.PLAYER_SIXTH_OPTION,
        MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION);

    private static Set<MenuAction> doorClicks()
    {
        Set<MenuAction> clicks = EnumSet.copyOf(OBJECT_CLICKS);
        clicks.add(MenuAction.EXAMINE_OBJECT);
        return clicks;
    }

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private BetterDriftNetConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private DriftNetSceneOverlay sceneOverlay;

    @Inject
    private RenderCallbackManager renderCallbackManager;

    @Inject
    private DriftNetTridentOverlay tridentOverlay;

    @Inject
    private DriftNetNumuliteOverlay numuliteOverlay;

    @Inject
    private KeyManager keyManager;

    @Inject
    private ClientThread clientThread;

    // The two net objects, tracked so the overlay can draw their clickbox.
    private GameObject net1Object;
    private GameObject net2Object;

    private final Map<Integer, Integer> taggedUntilTick = new HashMap<>();
    private NPC lastShoalInteraction;

    // The tagged shoals the scene overlay marks, collected once per game tick.
    private final List<NPC> taggedShoals = new ArrayList<>();

    // Catch counts of the two nets as of the last game tick, -1 when unread.
    private int net1Catch = -1;
    private int net2Catch = -1;

    // Harvest entry scene coords (packed) mapped to the catch varbit of the
    // nearer net, one slot per net. Fixed for a scene, so cleared on load.
    private final int[] harvestSceneKeys = {-1, -1};
    private final int[] harvestVarbits = {-1, -1};

    // Weapon slot item id from the last worn-container change, -1 for empty.
    private int wornWeaponId = WEAPON_UNKNOWN;

    // Bit n set when number key n selects a blocked dialog line. Written on
    // the client thread each client tick, read by the key listener on the
    // AWT thread, which must not touch client state itself.
    private volatile int blockedDialogDigits;

    // Set while the close guard is armed with the drift net window open, so
    // Escape (which closes it without a menu click) can be taken. Same
    // threading as blockedDialogDigits.
    private volatile boolean escapeBlocked;

    // Config values read on per-tick and per-frame paths, refreshed on
    // startUp and on any change to the group. ConfigChanged can arrive off
    // the client thread, hence volatile.
    private volatile boolean hideTaggedFish;
    private volatile boolean prioritizeUntaggedFish;
    private volatile TaggedFishMarker taggedFishMarker = TaggedFishMarker.OFF;
    private volatile boolean showNetClickbox;
    private volatile boolean blockClaimOption;
    private volatile String claimOptionText = "";
    private volatile boolean blockMovingFishOut;
    private volatile List<Pattern> allowListPatterns = List.of();
    private volatile boolean blockEarlyHarvest;
    private volatile int minFishToHarvest;
    private volatile boolean tridentWarningGuard;
    private volatile boolean tunnelDialogGuard;
    private volatile boolean deprioDoorWhileArmed;
    private volatile boolean highlightUnequippedTrident;

    private boolean bankedSinceOpen;
    private boolean interfaceWasOpen;
    private boolean blockMessageSent;

    // Area and zone flags, computed once per game tick so the per-frame and
    // per-client-tick paths read a field instead of mapping the player point.
    private boolean inArea;
    private boolean inFishingZone;

    // Set while the chat options interface is loaded, so the tunnel dialog
    // highlight only reads widgets while a dialog can be up.
    private boolean chatOptionsOpen;

    // Dialog lines turned green, with their colour before, so a guard turned
    // off (or the plugin stopping) can put them back. Client thread only.
    private final Map<Widget, Integer> tunnelHighlights = new IdentityHashMap<>();
    private final Map<Widget, Integer> tridentHighlights = new IdentityHashMap<>();

    // Lets a scene read queued by startUp skip itself once the plugin is off.
    private volatile boolean started;

    public void startUp()
    {
        log.debug("[state] startUp");
        started = true;
        refreshConfig();
        renderCallbackManager.register(this);
        bankedSinceOpen = false;
        interfaceWasOpen = false;
        blockMessageSent = false;
        taggedUntilTick.clear();
        taggedShoals.clear();
        lastShoalInteraction = null;
        net1Object = null;
        net2Object = null;
        net1Catch = -1;
        net2Catch = -1;
        clearHarvestSceneCache();
        wornWeaponId = WEAPON_UNKNOWN;
        blockedDialogDigits = 0;
        escapeBlocked = false;
        inArea = false;
        inFishingZone = false;
        // True so a dialog already open at startup is still checked; the first
        // client tick clears it when there is none.
        chatOptionsOpen = true;
        // ConfigChanged does not fire while the plugin is off, so a verdict
        // cached before a disable could be stale by now.
        tridentOverlay.clearTridentCache();
        eventBus.register(this);
        overlayManager.add(sceneOverlay);
        overlayManager.add(tridentOverlay);
        overlayManager.add(numuliteOverlay);
        keyManager.registerKeyListener(this);
        // Spawn events only fire on a scene load, so pick up the nets and the
        // worn weapon of the scene the plugin is enabled in.
        clientThread.invokeLater(this::readLoadedScene);
    }

    public void shutDown()
    {
        log.debug("[state] shutDown");
        started = false;
        keyManager.unregisterKeyListener(this);
        renderCallbackManager.unregister(this);
        overlayManager.remove(sceneOverlay);
        overlayManager.remove(tridentOverlay);
        overlayManager.remove(numuliteOverlay);
        eventBus.unregister(this);
        bankedSinceOpen = false;
        interfaceWasOpen = false;
        blockMessageSent = false;
        taggedUntilTick.clear();
        // taggedShoals is left for startUp to clear: a scene overlay frame
        // still in progress on the client thread may be iterating it.
        lastShoalInteraction = null;
        net1Object = null;
        net2Object = null;
        net1Catch = -1;
        net2Catch = -1;
        clearHarvestSceneCache();
        wornWeaponId = WEAPON_UNKNOWN;
        blockedDialogDigits = 0;
        escapeBlocked = false;
        inArea = false;
        inFishingZone = false;
        chatOptionsOpen = false;
        // The highlight maps are only touched on the client thread.
        clientThread.invoke(() ->
        {
            restoreHighlights(tunnelHighlights);
            restoreHighlights(tridentHighlights);
        });
    }

    /** Reads the net objects and the worn weapon from the scene already loaded. */
    private void readLoadedScene()
    {
        if (!started || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        readWornWeapon(client.getItemContainer(InventoryID.WORN));
        // Set now rather than on the next game tick, so the overlays draw at once.
        inArea = inDriftNetArea(client);
        inFishingZone = inArea && inDriftNetFishingZone(client);

        WorldView worldView = client.getTopLevelWorldView();
        Scene scene = worldView == null ? null : worldView.getScene();
        if (scene == null)
        {
            log.debug("[scene] readLoadedScene no scene, inArea={} inFishingZone={} wornWeaponId={}",
                inArea, inFishingZone, wornWeaponId);
            return;
        }
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
                        }
                    }
                }
            }
        }
        log.debug("[scene] readLoadedScene inArea={} inFishingZone={} net1={} net2={} wornWeaponId={}",
            inArea, inFishingZone, net1Object != null, net2Object != null, wornWeaponId);
    }

    private void refreshConfig()
    {
        hideTaggedFish = config.hideTaggedFish();
        prioritizeUntaggedFish = config.prioritizeUntaggedFish();
        taggedFishMarker = config.taggedFishMarker();
        showNetClickbox = config.showNetClickbox();
        blockClaimOption = config.blockClaimOption();
        claimOptionText = config.claimOptionText().trim();
        blockMovingFishOut = config.blockMovingFishOut();
        allowListPatterns = parseGlobList(config.stillMovableItems());
        blockEarlyHarvest = config.blockEarlyHarvest();
        minFishToHarvest = config.minFishToHarvest();
        tridentWarningGuard = config.tridentWarningGuard();
        tunnelDialogGuard = config.tunnelDialogGuard();
        deprioDoorWhileArmed = config.deprioDoorWhileArmed();
        highlightUnequippedTrident = config.highlightUnequippedTrident();
        log.debug("[config] hideTagged={} prioritizeUntagged={} marker={} claim={} fishMove={} earlyHarvest={} min={} "
                + "tridentGuard={} tunnelGuard={} doorDeprio={} bankBeforeClose={}",
            hideTaggedFish, prioritizeUntaggedFish, taggedFishMarker, blockClaimOption, blockMovingFishOut,
            blockEarlyHarvest, minFishToHarvest, tridentWarningGuard, tunnelDialogGuard, deprioDoorWhileArmed,
            config.bankBeforeClose());
    }

    /**
     * Sinks the plant door's "Navigate" (and Examine) below "Walk here" while
     * a weapon is worn; it can't be swum through armed. Runs after
     * RuneLite's own menu sorting.
     */
    @Subscribe(priority = -1)
    public void onPostMenuSort(PostMenuSort event)
    {
        // Cached gates first: most menu builds have neither a weapon worn with
        // the door deprio on nor a tagged shoal, and then the menu is not read.
        boolean door = deprioDoorWhileArmed && inArea && weaponEquipped();
        boolean shoals = prioritizeUntaggedFish && !taggedUntilTick.isEmpty();
        if ((!door && !shoals) || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        if (door)
        {
            deprioritizeDoor();
        }
        if (shoals)
        {
            prioritizeUntaggedShoals();
        }
    }

    /**
     * Puts untagged shoals above tagged ones where their entries overlap, so a
     * second left-click tags the other shoal instead of re-clicking the first.
     * Only the shoal entries move; every other entry keeps its slot.
     */
    private void prioritizeUntaggedShoals()
    {
        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        List<Integer> slots = new ArrayList<>();
        List<MenuEntry> tagged = new ArrayList<>();
        List<MenuEntry> untagged = new ArrayList<>();

        for (int i = 0; i < entries.length; i++)
        {
            int state = shoalState(entries[i]);
            if (state == 0)
            {
                continue;
            }
            slots.add(i);
            if (state == SHOAL_TAGGED)
            {
                tagged.add(entries[i]);
            }
            else
            {
                untagged.add(entries[i]);
            }
        }

        if (tagged.isEmpty() || untagged.isEmpty())
        {
            return;
        }

        // Entries run bottom-to-top, so the tagged ones take the lower slots.
        MenuEntry[] reordered = entries.clone();
        int slot = 0;
        for (MenuEntry entry : tagged)
        {
            reordered[slots.get(slot++)] = entry;
        }
        for (MenuEntry entry : untagged)
        {
            reordered[slots.get(slot++)] = entry;
        }
        if (!Arrays.equals(reordered, entries))
        {
            int moved = 0;
            for (int i = 0; i < entries.length; i++)
            {
                if (reordered[i] != entries[i])
                {
                    moved++;
                }
            }
            log.debug("[shoal] reordered, moved={}", moved);
            menu.setMenuEntries(reordered);
        }
    }

    /** Keeps tagged shoals out of the render while their tag holds. */
    @Override
    public boolean addEntity(Renderable renderable, boolean drawingUI)
    {
        if (taggedUntilTick.isEmpty() || !hideTaggedFish || !(renderable instanceof NPC))
        {
            return true;
        }
        return !isTaggedShoal((NPC) renderable);
    }

    /** True for a fish shoal prodded recently enough that its tag has not expired. */
    private boolean isTaggedShoal(NPC npc)
    {
        // onGameTick drops expired tags, so a tag still in the map holds.
        return npc.getId() == NpcID.FOSSIL_FISH_SHOAL && taggedUntilTick.containsKey(npc.getIndex());
    }

    /** SHOAL_TAGGED, SHOAL_UNTAGGED, or 0 when the entry is not a fish shoal. */
    private int shoalState(MenuEntry entry)
    {
        NPC npc = entry.getNpc();
        if (npc == null || npc.getId() != NpcID.FOSSIL_FISH_SHOAL)
        {
            return 0;
        }
        return isTaggedShoal(npc) ? SHOAL_TAGGED : SHOAL_UNTAGGED;
    }

    private void deprioritizeDoor()
    {
        // The caller has checked the setting, the area and the worn weapon; the door scan
        // allocates nothing, so menus without the door cost no list building.
        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        if (!containsDoorEntry(entries))
        {
            return;
        }

        List<MenuEntry> deprio = new ArrayList<>();
        List<MenuEntry> rest = new ArrayList<>();

        for (MenuEntry entry : entries)
        {
            if (isDoorEntry(entry))
            {
                deprio.add(entry);
            }
            else
            {
                rest.add(entry);
            }
        }

        int walkIndex = findWalkAnchor(rest);
        if (deprio.isEmpty() || walkIndex < 0)
        {
            // No door entries, or no "Walk here" to place them against. Leaving the
            // order alone beats guessing an anchor.
            return;
        }

        rest.addAll(walkIndex, deprio);
        MenuEntry[] reordered = rest.toArray(new MenuEntry[0]);
        if (!Arrays.equals(reordered, entries))
        {
            log.debug("[door] deprioritized, moved={}", deprio.size());
            menu.setMenuEntries(reordered);
        }
    }

    private static boolean containsDoorEntry(MenuEntry[] entries)
    {
        for (MenuEntry entry : entries)
        {
            if (isDoorEntry(entry))
            {
                return true;
            }
        }
        return false;
    }

    /** Any object action (Navigate, ...) or Examine on the plant door. */
    private static boolean isDoorEntry(MenuEntry entry)
    {
        return DOOR_CLICKS.contains(entry.getType()) && entry.getIdentifier() == DOOR_ID;
    }

    /**
     * Index of "Walk here" so deprioritized entries land below it, or -1 when the menu
     * has none. Entries run bottom-to-top, so inserting at this index puts the door
     * under "Walk here".
     */
    private int findWalkAnchor(List<MenuEntry> entries)
    {
        for (int i = 0; i < entries.size(); i++)
        {
            if (entries.get(i).getType() == MenuAction.WALK)
            {
                return i;
            }
        }
        return -1;
    }

    private boolean weaponEquipped()
    {
        return equippedWeaponId() > -1;
    }

    /**
     * The weapon slot item id, or -1 when the slot is empty. Cached from the
     * worn container's changes; read live until the first one has been seen.
     */
    int equippedWeaponId()
    {
        if (wornWeaponId == WEAPON_UNKNOWN)
        {
            readWornWeapon(client.getItemContainer(InventoryID.WORN));
        }
        // Still unknown when there is no worn container yet: an empty slot,
        // and the next call reads again.
        return wornWeaponId == WEAPON_UNKNOWN ? -1 : wornWeaponId;
    }

    private void readWornWeapon(ItemContainer worn)
    {
        if (worn == null)
        {
            // Not cached, so the next read retries.
            wornWeaponId = WEAPON_UNKNOWN;
            return;
        }
        // An empty equipment slot is an Item with id -1, not a null.
        Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        wornWeaponId = weapon == null || weapon.getId() <= -1 ? -1 : weapon.getId();
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.WORN)
        {
            readWornWeapon(event.getItemContainer());
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        // Cached gates ahead of the game state read: most client ticks are
        // spent outside the area with no dialog up.
        if (!chatOptionsOpen && !inArea)
        {
            setEscapeBlocked(false);
            return;
        }
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            setBlockedDialogDigits(0);
            setEscapeBlocked(false);
            return;
        }

        // The tunnel is outside the underwater region, so this can't sit behind
        // the area gate; the chat options flag keeps it off most client ticks.
        updateDialogGuards();

        if (!inArea)
        {
            setEscapeBlocked(false);
            return;
        }

        // The window going from closed to open means a fresh unbanked catch.
        boolean interfaceOpen = isDriftNetOpen();
        if (interfaceOpen && !interfaceWasOpen)
        {
            armCloseGuard();
        }
        interfaceWasOpen = interfaceOpen;
        setEscapeBlocked(interfaceOpen && !bankedSinceOpen && config.bankBeforeClose());
    }

    /** Writes the Escape block flag, logging only when it changes. */
    private void setEscapeBlocked(boolean blocked)
    {
        if (escapeBlocked != blocked)
        {
            escapeBlocked = blocked;
            log.debug("[close] escape {}", blocked ? "blocked" : "allowed");
        }
    }

    /**
     * Highlights the safe line of the tunnel and trident dialogs and records
     * which number keys would pick the blocked line, for the key listener.
     */
    private void updateDialogGuards()
    {
        boolean tunnel = tunnelDialogGuard;
        boolean trident = tridentWarningGuard && inArea && inFishingZone;
        // A guard turned off puts back the colours it changed.
        if (!tunnelDialogGuard && !tunnelHighlights.isEmpty())
        {
            restoreHighlights(tunnelHighlights);
        }
        if (!tridentWarningGuard && !tridentHighlights.isEmpty())
        {
            restoreHighlights(tridentHighlights);
        }
        if (!chatOptionsOpen || (!tunnel && !trident))
        {
            setBlockedDialogDigits(0);
            return;
        }

        Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        if (options == null)
        {
            // Gone without a WidgetClosed (or never loaded): stop checking.
            chatOptionsOpen = false;
            setBlockedDialogDigits(0);
            forgetHighlights();
            return;
        }
        Widget[] lines = options.getDynamicChildren();
        if (lines == null)
        {
            setBlockedDialogDigits(0);
            return;
        }

        int digits = 0;
        if (tunnel && isInstanceEntryDialog(lines))
        {
            highlightInstanceEntryDialog(lines);
            digits |= blockedDigits(lines, firstTunnelOptionIndex(lines), DONT_ENTER);
        }
        if (trident && containsPlayItSafe(lines))
        {
            highlightTridentDialog(lines);
            digits |= blockedDigits(lines, firstTridentOptionIndex(lines), PLAY_IT_SAFE);
        }
        setBlockedDialogDigits(digits);
    }

    /** Writes the blocked digit mask, logging only when it changes. */
    private void setBlockedDialogDigits(int digits)
    {
        int old = blockedDialogDigits;
        blockedDialogDigits = digits;
        if (old != digits)
        {
            log.debug("[dialog] blocked digits {} -> {}", Integer.toBinaryString(old), Integer.toBinaryString(digits));
        }
    }

    /**
     * Removes the blocked entries as the menu is built, so the left-click option
     * and the opened menu both go without them.
     */
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (!inArea || (!hideTaggedFish && !blockEarlyHarvest && !blockClaimOption && !blockMovingFishOut))
        {
            return;
        }
        MenuEntry entry = event.getMenuEntry();
        String rule = blockedRule(entry);
        if (rule != null)
        {
            try
            {
                client.getMenu().removeMenuEntry(entry);
                log.debug("[menu] removed option={} target={} rule={}", entry.getOption(), entry.getTarget(), rule);
            }
            catch (IllegalArgumentException e)
            {
                // Another subscriber (or a second copy of this plugin) removed
                // it first; the menu throws on a second removal.
            }
        }
    }

    /**
     * The rule name that blocks an entry, or null when it stays. The claim and fish-move guards act on the open reward interface; the
     * early-harvest gate acts on the world "Harvest" object click while the
     * interface is still closed, so it isn't gated on the window being open.
     * Both interface guards lift on the same signal as the close guard: once the
     * catch is banked, what is left is the unbankable remainder and taking it to
     * inventory is the only way to keep it.
     */
    private String blockedRule(MenuEntry entry)
    {
        if (hideTaggedFish && !taggedUntilTick.isEmpty() && shoalState(entry) == SHOAL_TAGGED)
        {
            return "hide tagged shoal";
        }
        if (blockEarlyHarvest && isBlockedEarlyHarvest(entry, minFishToHarvest))
        {
            return "early harvest (net1=" + net1Catch + " net2=" + net2Catch + " min=" + minFishToHarvest + ")";
        }
        if ((!blockClaimOption && !blockMovingFishOut) || bankedSinceOpen || !isDriftNetEntry(entry))
        {
            return null;
        }
        // The window check is a widget read, so it only runs for a matching entry.
        if (isBlockedClaim(entry))
        {
            return isDriftNetOpen() ? "claim" : null;
        }
        if (isBlockedFishMove(entry))
        {
            return isDriftNetOpen() ? "fish move" : null;
        }
        return null;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // Anything other than logged in invalidates the tracked objects; spawns
        // re-fire on the next scene load.
        if (event.getGameState() != GameState.LOGGED_IN)
        {
            net1Object = null;
            net2Object = null;
            inArea = false;
            inFishingZone = false;
            net1Catch = -1;
            net2Catch = -1;
            clearHarvestSceneCache();
            log.debug("[state] game state {}, tracked objects cleared", event.getGameState());
        }
        // The worn container is sent again after a login or hop. NPC indices
        // restart with the new session or world, so the tags go too; a scene
        // load needs no clear, as the NPCs it drops fire NpcDespawned.
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            wornWeaponId = WEAPON_UNKNOWN;
            taggedUntilTick.clear();
            taggedShoals.clear();
            lastShoalInteraction = null;
            log.debug("[state] game state {}, tags and worn weapon reset", event.getGameState());
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        trackNet(event.getGameObject());
    }

    private void trackNet(GameObject object)
    {
        if (object.getId() == ObjectID.FOSSIL_DRIFT_NET1_MULTI)
        {
            net1Object = object;
            log.debug("[scene] tracked net1 objectId={}", object.getId());
        }
        else if (object.getId() == ObjectID.FOSSIL_DRIFT_NET2_MULTI)
        {
            net2Object = object;
            log.debug("[scene] tracked net2 objectId={}", object.getId());
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        GameObject object = event.getGameObject();
        if (object == net1Object)
        {
            net1Object = null;
        }
        else if (object == net2Object)
        {
            net2Object = null;
        }
    }

    /** The two drift net objects currently in the scene, either may be null. */
    GameObject[] getNets()
    {
        return new GameObject[] {net1Object, net2Object};
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event)
    {
        if (event.getSource() != client.getLocalPlayer())
        {
            return;
        }
        Actor target = event.getTarget();
        if (target instanceof NPC && ((NPC) target).getId() == NpcID.FOSSIL_FISH_SHOAL)
        {
            if (lastShoalInteraction != target)
            {
                log.debug("[tag] interacting with shoal index={}", ((NPC) target).getIndex());
            }
            lastShoalInteraction = (NPC) target;
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!isProdMessage(event.getType(), event.getMessage()))
        {
            return;
        }
        if (lastShoalInteraction == null)
        {
            log.debug("[tag] prod message but no shoal interaction, nothing tagged");
            return;
        }
        int expiry = client.getTickCount() + config.tagTimeoutTicks();
        taggedUntilTick.put(lastShoalInteraction.getIndex(), expiry);
        Player local = client.getLocalPlayer();
        log.debug("[tag] tagged index={} npcAt={} playerAt={} expiresTick={}", lastShoalInteraction.getIndex(),
            lastShoalInteraction.getWorldLocation(), local == null ? null : local.getWorldLocation(), expiry);
        refreshTaggedShoals();
    }

    static boolean isProdMessage(ChatMessageType type, String message)
    {
        // Game text only, so a player typing the phrase tags nothing. RuneLite
        // core's drift net plugin reads the prod message as SPAM (filterable).
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
        {
            return false;
        }
        return Text.removeTags(message).toLowerCase(Locale.ROOT).contains(PROD_MESSAGE);
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        boolean tagDropped = !taggedUntilTick.isEmpty() && taggedUntilTick.remove(event.getNpc().getIndex()) != null;
        // Every shoal, tagged or not: a caught one despawns on its net's entry tile.
        if (inArea && log.isDebugEnabled() && event.getNpc().getId() == NpcID.FOSSIL_FISH_SHOAL)
        {
            log.debug("[shoal] despawned index={} at={} tagDropped={}", event.getNpc().getIndex(),
                WorldPoint.fromLocalInstance(client, event.getNpc().getLocalLocation()), tagDropped);
        }
        if (!taggedShoals.isEmpty())
        {
            taggedShoals.remove(event.getNpc());
        }
        if (event.getNpc() == lastShoalInteraction)
        {
            lastShoalInteraction = null;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        inArea = inDriftNetArea(client);
        // The zone lies inside the area, so outside it the zone check is skipped.
        inFishingZone = inArea && inDriftNetFishingZone(client);

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

        if (!taggedUntilTick.isEmpty())
        {
            int now = client.getTickCount();
            int before = taggedUntilTick.size();
            taggedUntilTick.values().removeIf(expiry -> expiry <= now);
            if (taggedUntilTick.size() != before)
            {
                log.debug("[tag] expired {}, remaining={}", before - taggedUntilTick.size(), taggedUntilTick.size());
            }
        }
        dropTagsAtNets();
        refreshTaggedShoals();
    }

    /**
     * A tagged shoal that reaches a net's entry tile loses its prod either way: a net
     * taking fish catches it (and it despawns), one that is unset or full
     * turns it away unprodded, free to prod again. The tag is cut short rather
     * than dropped: a shoal being caught can sit on the tile a tick or two
     * first, and should stay hidden until it despawns.
     */
    private void dropTagsAtNets()
    {
        if (taggedUntilTick.isEmpty() || !inArea)
        {
            return;
        }
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return;
        }
        int endsAt = client.getTickCount() + ENTRY_GRACE_TICKS;
        for (NPC npc : worldView.npcs())
        {
            Integer expiry = npc == null ? null : taggedUntilTick.get(npc.getIndex());
            if (expiry == null || expiry <= endsAt)
            {
                continue;
            }
            WorldPoint wp = WorldPoint.fromLocalInstance(client, npc.getLocalLocation());
            if (wp == null)
            {
                continue;
            }
            boolean atNet1 = NET1_ENTRY.equals(wp);
            if (atNet1 || NET2_ENTRY.equals(wp))
            {
                taggedUntilTick.put(npc.getIndex(), endsAt);
                log.debug("[tag] shoal reached net{}, tag ends tick={} index={} at={}", atNet1 ? 1 : 2, endsAt, npc.getIndex(), wp);
            }
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
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

    /** Collects the tagged shoals the scene overlay marks, so it does not scan NPCs per frame. */
    private void refreshTaggedShoals()
    {
        if (!taggedShoals.isEmpty())
        {
            taggedShoals.clear();
        }
        // The overlay draws nothing unless both are set, so nothing is collected
        // either. Turning one on refreshes the list at once (onConfigChanged);
        // turning one off is checked by the overlay per frame.
        if (taggedUntilTick.isEmpty() || !inArea || !hideTaggedFish || taggedFishMarker == TaggedFishMarker.OFF)
        {
            return;
        }
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return;
        }
        for (NPC npc : worldView.npcs())
        {
            if (isTaggedShoal(npc))
            {
                taggedShoals.add(npc);
            }
        }
    }

    /** The tagged shoals in the area, as of the last game tick or prod message. */
    List<NPC> getTaggedShoals()
    {
        return taggedShoals;
    }

    TaggedFishMarker getTaggedFishMarker()
    {
        return taggedFishMarker;
    }

    boolean isHideTaggedFish()
    {
        return hideTaggedFish;
    }

    boolean isShowNetClickbox()
    {
        return showNetClickbox;
    }

    boolean isHighlightUnequippedTrident()
    {
        return highlightUnequippedTrident;
    }

    /** True in the underwater region, as of the last game tick. */
    boolean isInArea()
    {
        return inArea;
    }

    /** True in the drift-net fishing zone, as of the last game tick. */
    boolean isInFishingZone()
    {
        return inFishingZone;
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == InterfaceID.FOSSIL_DRIFTNET)
        {
            armCloseGuard();
        }
        else if (event.getGroupId() == InterfaceID.CHATMENU)
        {
            chatOptionsOpen = true;
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        if (event.getGroupId() == InterfaceID.CHATMENU)
        {
            chatOptionsOpen = false;
            setBlockedDialogDigits(0);
            forgetHighlights();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (BetterDriftNetConfig.GROUP.equals(event.getGroup()))
        {
            refreshConfig();
            tridentOverlay.clearTridentCache();
            if (HIDE_TAGGED_KEY.equals(event.getKey()) || MARKER_KEY.equals(event.getKey()))
            {
                // Collected per game tick; this draws a setting turned on
                // within a frame instead.
                clientThread.invokeLater(() ->
                {
                    if (started)
                    {
                        refreshTaggedShoals();
                    }
                });
            }
        }
    }

    private void armCloseGuard()
    {
        bankedSinceOpen = false;
        blockMessageSent = false;
        log.debug("[close] guard armed");
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        // Ahead of every gate. The confirmation replaces the item grid, so
        // isDriftNetOpen() reads false while it is up. Unconditional on purpose: a
        // refused bank is not fixable from inside the window, so staying armed
        // would trap the player. Read by the fish-move block as well, so it cannot
        // sit behind the close guard's own setting either.
        if (event.getParam1() == InterfaceID.FossilDriftnet.CONFIRM_BANK
            || event.getParam1() == InterfaceID.FossilDriftnet.CONFIRM_DESTROY)
        {
            if (!bankedSinceOpen)
            {
                log.debug("[close] guard disarmed ({})",
                    event.getParam1() == InterfaceID.FossilDriftnet.CONFIRM_BANK ? "bank confirmed" : "destroy confirmed");
            }
            bankedSinceOpen = true;
            return;
        }

        if (tunnelDialogGuard && isDontEnterClick(event))
        {
            event.consume();
            log.debug("[dialog] blocked Don't enter click");
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "[Safety] 'Don't enter' blocked. Pick Enter instance.", null);
            }
            return;
        }

        if (!inDriftNetArea())
        {
            return;
        }

        if (tridentWarningGuard && inDriftNetFishingZone() && isPlayItSafeClick(event))
        {
            event.consume();
            log.debug("[dialog] blocked Play it safe click");
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "[Safety] 'Play it safe' blocked. Wield it and stay out of deep water.", null);
            }
            return;
        }

        if (!isDriftNetOpen() || !config.bankBeforeClose() || bankedSinceOpen)
        {
            return;
        }

        // Everything on the window itself stays clickable except its own Close;
        // a Close on any other interface is left alone.
        if (!SCENE_CLICKS.contains(event.getMenuAction()) && !isDriftNetClose(event))
        {
            return;
        }

        event.consume();
        log.debug("[close] consumed {} option={}", event.getMenuAction(), event.getMenuOption());
        if (config.showBlockMessages() && !blockMessageSent)
        {
            blockMessageSent = true;
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                "[Safety] Bank the catch first (Bank all + confirm).", null);
        }
    }

    /** A "Close" on a component of the drift net window itself. */
    private static boolean isDriftNetClose(MenuOptionClicked event)
    {
        return WidgetUtil.componentToInterface(event.getParam1()) == InterfaceID.FOSSIL_DRIFTNET
            && "Close".equalsIgnoreCase(Text.removeTags(event.getMenuOption()));
    }

    // The number keys pick a dialog line without going through
    // MenuOptionClicked. The pressed key and the typed key arrive as separate
    // events, so consuming one does not suppress the other and both are taken.
    // These run on the AWT thread, so they only read the digits the client
    // tick recorded and never touch client state.
    @Override
    public void keyPressed(KeyEvent e)
    {
        consumeIfBlockedDialogDigit(e);
        consumeIfBlockedEscape(e);
    }

    @Override
    public void keyTyped(KeyEvent e)
    {
        consumeIfBlockedDialogDigit(e);
        consumeIfBlockedEscape(e);
    }

    // Escape closes the drift net window without a menu click, discarding an
    // unbanked catch the same way its Close button does.
    private void consumeIfBlockedEscape(KeyEvent e)
    {
        if (!escapeBlocked
            || (e.getKeyCode() != KeyEvent.VK_ESCAPE && e.getKeyChar() != KeyEvent.VK_ESCAPE))
        {
            return;
        }

        e.consume();
        if (e.getID() != KeyEvent.KEY_PRESSED)
        {
            return;
        }
        log.debug("[close] consumed escape");
        clientThread.invokeLater(() ->
        {
            if (started && config.showBlockMessages() && !blockMessageSent)
            {
                blockMessageSent = true;
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "[Safety] Bank the catch first (Bank all + confirm).", null);
            }
        });
    }

    @Override
    public void keyReleased(KeyEvent e)
    {
    }

    private void consumeIfBlockedDialogDigit(KeyEvent e)
    {
        int blocked = blockedDialogDigits;
        if (blocked == 0)
        {
            return;
        }

        int digit = digitOf(e);
        if (digit > 0 && (blocked & (1 << digit)) != 0)
        {
            e.consume();
            log.debug("[dialog] consumed digit key {}", digit);
        }
    }

    private static int digitOf(KeyEvent e)
    {
        char ch = e.getKeyChar();
        if (ch >= '1' && ch <= '9')
        {
            return ch - '0';
        }

        int code = e.getKeyCode();
        if (code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9)
        {
            return code - KeyEvent.VK_0;
        }
        if (code >= KeyEvent.VK_NUMPAD1 && code <= KeyEvent.VK_NUMPAD9)
        {
            return code - KeyEvent.VK_NUMPAD0;
        }
        return -1;
    }

    /**
     * Bit n set when the 1-based digit n selects the blocked line ("Play it
     * safe." or "Don't enter."). The dialog title takes a child slot of its
     * own, so the offset comes from locating the first child that is one of
     * the two option lines rather than from a fixed index.
     */
    private static int blockedDigits(Widget[] lines, int firstOptionIndex, String blockedLine)
    {
        if (firstOptionIndex < 0)
        {
            return 0;
        }

        int digits = 0;
        for (int digit = 1; digit <= 9; digit++)
        {
            int index = firstOptionIndex + digit - 1;
            if (index >= lines.length)
            {
                break;
            }
            if (lines[index] == null)
            {
                continue;
            }
            String text = lines[index].getText();
            if (text != null && blockedLine.equalsIgnoreCase(Text.removeTags(text)))
            {
                digits |= 1 << digit;
            }
        }
        return digits;
    }

    private static int firstTridentOptionIndex(Widget[] lines)
    {
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i] == null)
            {
                continue;
            }
            String text = lines[i].getText();
            if (text == null)
            {
                continue;
            }
            String line = Text.removeTags(text);
            if (PLAY_IT_SAFE.equalsIgnoreCase(line) || line.toLowerCase(Locale.ROOT).contains(WIELD_ANYWAY_MARKER))
            {
                return i;
            }
        }
        return -1;
    }

    private static int firstTunnelOptionIndex(Widget[] lines)
    {
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i] == null)
            {
                continue;
            }
            String text = lines[i].getText();
            if (text == null)
            {
                continue;
            }
            String line = Text.removeTags(text);
            if (ENTER_INSTANCE.equalsIgnoreCase(line) || DONT_ENTER.equalsIgnoreCase(line))
            {
                return i;
            }
        }
        return -1;
    }

    /** Whether a menu entry belongs to the drift net interface itself. */
    private static boolean isDriftNetEntry(MenuEntry entry)
    {
        return WidgetUtil.componentToInterface(entry.getParam1()) == InterfaceID.FOSSIL_DRIFTNET;
    }

    private boolean isBlockedClaim(MenuEntry entry)
    {
        // Scoped to the window's own entries: the option text is user-configurable, and
        // a common word would otherwise strip matching entries elsewhere on screen.
        String blocked = claimOptionText;
        return blockClaimOption && !blocked.isEmpty() && isDriftNetEntry(entry)
            && blocked.equalsIgnoreCase(Text.removeTags(entry.getOption()));
    }

    private boolean isBlockedFishMove(MenuEntry entry)
    {
        // Once the catch is banked, what is left is the unbankable remainder, so
        // moving it out is the only way to keep it.
        if (!blockMovingFishOut || bankedSinceOpen || !isDriftNetEntry(entry)
            || !MOVE_TO_INVENTORY.equalsIgnoreCase(Text.removeTags(entry.getOption())))
        {
            return false;
        }

        String target = Text.removeTags(entry.getTarget()).toLowerCase(Locale.ROOT);
        for (Pattern allowed : allowListPatterns)
        {
            if (allowed.matcher(target).matches())
            {
                return false;
            }
        }
        return true;
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
                return harvestVarbits[i];
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

        int catchVarbit = nearestTileDistance(wp, NET1_TILES) <= nearestTileDistance(wp, NET2_TILES)
            ? VarbitID.FOSSIL_DRIFT_NET1_CATCH
            : VarbitID.FOSSIL_DRIFT_NET2_CATCH;
        // One slot per net: a key for net 1 goes in slot 0, net 2 in slot 1.
        int slot = catchVarbit == VarbitID.FOSSIL_DRIFT_NET1_CATCH ? 0 : 1;
        harvestSceneKeys[slot] = key;
        harvestVarbits[slot] = catchVarbit;
        return catchVarbit;
    }

    private void clearHarvestSceneCache()
    {
        Arrays.fill(harvestSceneKeys, -1);
        Arrays.fill(harvestVarbits, -1);
    }

    /** An inclusive west-to-east row of plane-1 tiles. */
    private static WorldPoint[] tileRow(int xMin, int xMax, int y)
    {
        WorldPoint[] tiles = new WorldPoint[xMax - xMin + 1];
        for (int i = 0; i < tiles.length; i++)
        {
            tiles[i] = new WorldPoint(xMin + i, y, 1);
        }
        return tiles;
    }

    /** An inclusive south-to-north column of plane-1 tiles. */
    private static WorldPoint[] tileColumn(int x, int yMin, int yMax)
    {
        WorldPoint[] tiles = new WorldPoint[yMax - yMin + 1];
        for (int i = 0; i < tiles.length; i++)
        {
            tiles[i] = new WorldPoint(x, yMin + i, 1);
        }
        return tiles;
    }

    /** Squared distance (plane ignored) from a point to the nearest given tile. */
    private static int nearestTileDistance(WorldPoint from, WorldPoint[] tiles)
    {
        int best = Integer.MAX_VALUE;
        for (WorldPoint tile : tiles)
        {
            int dx = from.getX() - tile.getX();
            int dy = from.getY() - tile.getY();
            int dist = dx * dx + dy * dy;
            if (dist < best)
            {
                best = dist;
            }
        }
        return best;
    }

    /**
     * Green-highlights the "wield it anyway" line of the deep-water trident
     * warning dialog. The colour before is kept in tridentHighlights.
     */
    private void highlightTridentDialog(Widget[] lines)
    {
        for (Widget line : lines)
        {
            String text = line == null ? null : line.getText();
            if (text != null
                && Text.removeTags(text).toLowerCase(Locale.ROOT).contains(WIELD_ANYWAY_MARKER))
            {
                highlight(line, tridentHighlights);
            }
        }
    }

    /**
     * Green-highlights "Enter instance." on the tunnel's already-paid dialog. The
     * colour before is kept in tunnelHighlights.
     */
    private void highlightInstanceEntryDialog(Widget[] lines)
    {
        for (Widget line : lines)
        {
            String text = line == null ? null : line.getText();
            if (text != null && ENTER_INSTANCE.equalsIgnoreCase(Text.removeTags(text)))
            {
                highlight(line, tunnelHighlights);
            }
        }
    }

    private static void highlight(Widget line, Map<Widget, Integer> highlights)
    {
        if (!highlights.containsKey(line))
        {
            highlights.put(line, line.getTextColor());
            log.debug("[dialog] highlight applied: {}", Text.removeTags(line.getText()));
        }
        line.setTextColor(HIGHLIGHT_GREEN);
    }

    private static void restoreHighlights(Map<Widget, Integer> highlights)
    {
        if (!highlights.isEmpty())
        {
            log.debug("[dialog] highlights restored: {}", highlights.size());
        }
        for (Map.Entry<Widget, Integer> lit : highlights.entrySet())
        {
            lit.getKey().setTextColor(lit.getValue());
        }
        highlights.clear();
    }

    /** The dialog is gone, and its lines with it: nothing left to restore. */
    private void forgetHighlights()
    {
        tunnelHighlights.clear();
        tridentHighlights.clear();
    }

    /** True when the click landed on the tunnel dialog's "Don't enter." line. */
    private boolean isDontEnterClick(MenuOptionClicked event)
    {
        if (event.getParam1() != InterfaceID.Chatmenu.OPTIONS)
        {
            return false;
        }
        Widget[] lines = dialogOptionLines();
        int index = event.getParam0();
        if (lines == null || index < 0 || index >= lines.length || lines[index] == null
            || !isInstanceEntryDialog(lines))
        {
            return false;
        }
        String text = lines[index].getText();
        return text != null && DONT_ENTER.equalsIgnoreCase(Text.removeTags(text));
    }

    /** Both markers together identify the tunnel's already-paid dialog. */
    private static boolean isInstanceEntryDialog(Widget[] lines)
    {
        boolean enter = false;
        boolean freeAccess = false;
        for (Widget line : lines)
        {
            String text = line == null ? null : line.getText();
            if (text == null)
            {
                continue;
            }
            String plain = Text.removeTags(text);
            enter |= ENTER_INSTANCE.equalsIgnoreCase(plain);
            freeAccess |= plain.toLowerCase(Locale.ROOT).contains(FREE_ACCESS_MARKER);
        }
        return enter && freeAccess;
    }

    private Widget[] dialogOptionLines()
    {
        Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        return options == null ? null : options.getDynamicChildren();
    }

    private boolean containsPlayItSafe(Widget[] lines)
    {
        for (Widget line : lines)
        {
            String text = line == null ? null : line.getText();
            if (text != null && PLAY_IT_SAFE.equalsIgnoreCase(Text.removeTags(text)))
            {
                return true;
            }
        }
        return false;
    }

    /** True when the click landed on the dialog's "Play it safe." line. */
    private boolean isPlayItSafeClick(MenuOptionClicked event)
    {
        if (event.getParam1() != InterfaceID.Chatmenu.OPTIONS)
        {
            return false;
        }
        Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        if (options == null)
        {
            return false;
        }
        Widget[] lines = options.getDynamicChildren();
        int index = event.getParam0();
        if (lines == null || index < 0 || index >= lines.length || lines[index] == null)
        {
            return false;
        }
        String text = lines[index].getText();
        return text != null && PLAY_IT_SAFE.equalsIgnoreCase(Text.removeTags(text));
    }

    private boolean isDriftNetOpen()
    {
        Widget w = client.getWidget(InterfaceID.FossilDriftnet.INV);
        return w != null && !w.isHidden();
    }

    private boolean inDriftNetArea()
    {
        return inDriftNetArea(client);
    }

    /** Cached once per game tick in inArea for the per-frame paths. */
    static boolean inDriftNetArea(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null || !worldView.isInstance())
        {
            return false;
        }
        WorldPoint point = playerPoint(client);
        return point != null && point.getRegionID() == UNDERWATER_REGION;
    }

    /**
     * The drift-net fishing zone proper. The interface-based guards self-scope
     * via the open window, but the trident dialog appears anywhere underwater,
     * so it needs the tighter box.
     */
    private boolean inDriftNetFishingZone()
    {
        return inDriftNetFishingZone(client);
    }

    /** Cached once per game tick in inFishingZone for the per-frame paths. */
    static boolean inDriftNetFishingZone(Client client)
    {
        WorldPoint point = playerPoint(client);
        return point != null
            && point.getX() >= NET_ZONE_X_MIN && point.getX() <= NET_ZONE_X_MAX
            && point.getY() >= NET_ZONE_Y_MIN && point.getY() <= NET_ZONE_Y_MAX;
    }

    private static WorldPoint playerPoint(Client client)
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

    /** Shared with the trident overlay. */
    static List<Pattern> parseGlobList(String csv)
    {
        List<Pattern> patterns = new ArrayList<>();
        for (String entry : csv.split(","))
        {
            String trimmed = entry.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            patterns.add(globToPattern(trimmed));
        }
        return patterns;
    }

    private static Pattern globToPattern(String glob)
    {
        String[] segments = glob.toLowerCase(Locale.ROOT).split("\\*", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < segments.length; i++)
        {
            if (i > 0)
            {
                regex.append(".*");
            }
            if (!segments[i].isEmpty())
            {
                regex.append(Pattern.quote(segments[i]));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
