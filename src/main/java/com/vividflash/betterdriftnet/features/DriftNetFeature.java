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
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
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
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
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
 * <p>This class holds the lifecycle and every event handler. The handlers
 * pass each event on, in a fixed order, to {@link NetTracker} (net objects,
 * catch counts, harvest gate), {@link ShoalTracker} (prod tags),
 * {@link DialogGuard} (tunnel and trident dialogs) and {@link CloseGuard}
 * (bank-before-close). The door deprio and the claim and fish-move rules
 * live here.
 *
 * <p>Sub-features live in three overlay classes: net clickboxes and tagged
 * shoal markers in {@link DriftNetSceneOverlay}, trident equip reminder in
 * {@link DriftNetTridentOverlay}, numulite balance warning in
 * {@link DriftNetNumuliteOverlay}.
 */
@Slf4j
@Singleton
public class DriftNetFeature implements KeyListener
{
    private static final String MOVE_TO_INVENTORY = "Move to Inventory";

    // The plant door into the drift-net area, "Navigate" in game. It cannot be
    // swum through with a weapon equipped, hence the while-armed deprioritization.
    // Not one of the FOSSIL_DRIFTNET_ENTRANCE ids, which are the paid-access tunnel.
    private static final int DOOR_ID = ObjectID.FOSSIL_UNDERWATER_DRIFTNET_CURTAIN;

    /** The entry kinds the plant door can contribute: its options plus Examine. */
    private static final Set<MenuAction> DOOR_CLICKS = doorClicks();

    /** One drift-net banking fee, in numulite. */
    static final int BANKING_FEE = 5;

    // Config keys whose change refreshes the tagged shoal list at once.
    private static final String HIDE_TAGGED_KEY = "hideTaggedFish";
    private static final String MARKER_KEY = "taggedFishMarker";
    private static final String CHASING_WEAPONS_KEY = "chasingWeaponNames";

    // Turning the trident highlight on classifies the held items.
    private static final String HIGHLIGHT_TRIDENT_KEY = "highlightUnequippedTrident";

    /** Weapon slot item id when the worn container has not been read yet. */
    private static final int WEAPON_UNKNOWN = -2;

    // Bits of guardAreaFlags.
    private static final int IN_AREA = 1;
    private static final int IN_FISHING_ZONE = 2;

    private static Set<MenuAction> doorClicks()
    {
        Set<MenuAction> clicks = EnumSet.copyOf(NetTracker.OBJECT_CLICKS);
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

    @Inject
    private NetTracker nets;

    @Inject
    private ShoalTracker shoals;

    @Inject
    private DialogGuard dialogs;

    @Inject
    private CloseGuard closeGuard;

    // Weapon slot item id from the last worn-container change, -1 for empty.
    private int wornWeaponId = WEAPON_UNKNOWN;

    // Config values read on per-tick and per-frame paths, refreshed on
    // startUp and on any change to the group. ConfigChanged can arrive off
    // the client thread, hence volatile.
    private volatile boolean blockClaimOption;
    private volatile String claimOptionText = "";
    private volatile boolean blockMovingFishOut;
    private volatile AllowList allowList = new AllowList(List.of());
    private volatile boolean deprioDoorWhileArmed;
    private volatile boolean highlightUnequippedTrident;
    private volatile boolean markLowNumulite;

    // Door entries moved in the last logged deprio, 0 once the door leaves the
    // menu. Client thread only, read for logging alone.
    private int doorMovedLogged;

    // Holds the door entries while the deprio reorders the menu. Client thread only.
    private MenuEntry[] doorScratch = new MenuEntry[0];

    // Area and zone flags, computed once per game tick so the per-frame and
    // per-client-tick paths read a field instead of mapping the player point.
    private boolean inArea;
    private boolean inFishingZone;
    // Set while the flags await the first game tick after startUp or a scene
    // load, when the guards read the player point live instead.
    private boolean areaFlagsStale;

    // Lets a scene read queued by startUp skip itself once the plugin is off.
    private volatile boolean started;

    public void startUp()
    {
        log.debug("[state] startUp");
        started = true;
        refreshConfig();
        refreshAllowList();
        // Cleared before the render callback and the subscribers are
        // registered, so nothing on the client thread reads the tag map yet.
        closeGuard.reset();
        shoals.clear();
        nets.clear();
        wornWeaponId = WEAPON_UNKNOWN;
        dialogs.startUp();
        inArea = false;
        inFishingZone = false;
        areaFlagsStale = true;
        // ConfigChanged does not fire while the plugin is off, so a verdict
        // cached before a disable could be stale by now.
        tridentOverlay.refreshPatterns();
        tridentOverlay.clearTridentCache();
        renderCallbackManager.register(shoals);
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
        renderCallbackManager.unregister(shoals);
        overlayManager.remove(sceneOverlay);
        overlayManager.remove(tridentOverlay);
        overlayManager.remove(numuliteOverlay);
        eventBus.unregister(this);
        closeGuard.reset();
        nets.clear();
        wornWeaponId = WEAPON_UNKNOWN;
        dialogs.shutDown();
        inArea = false;
        inFishingZone = false;
        // The tags, the marker list and the highlight maps are only touched on
        // the client thread, so they are cleared there. A clear still queued
        // when the plugin is turned on again is skipped: startUp has cleared
        // the tags already, and the new run puts back the old highlights
        // before it highlights a dialog.
        clientThread.invoke(() ->
        {
            if (!started)
            {
                shoals.clear();
                dialogs.restoreHighlights();
            }
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
        closeGuard.readWindowLoaded();
        // Set now rather than on the next game tick, so the overlays draw at once.
        readAreaFlags();
        if (inFishingZone && highlightUnequippedTrident)
        {
            classifyHeldItems();
        }
        if (!inArea)
        {
            // The nets only exist underwater, and their spawn events cover a later load.
            log.debug("[scene] readLoadedScene inArea={} inFishingZone={} net1={} net2={} wornWeaponId={}",
                inArea, inFishingZone, nets.getNet1() != null, nets.getNet2() != null, wornWeaponId);
            return;
        }
        shoals.readInteraction();

        WorldView worldView = client.getTopLevelWorldView();
        Scene scene = worldView == null ? null : worldView.getScene();
        if (scene == null)
        {
            log.debug("[scene] readLoadedScene no scene, inArea={} inFishingZone={} wornWeaponId={}",
                inArea, inFishingZone, wornWeaponId);
            return;
        }
        nets.scanScene(scene);
        log.debug("[scene] readLoadedScene inArea={} inFishingZone={} net1={} net2={} wornWeaponId={}",
            inArea, inFishingZone, nets.getNet1() != null, nets.getNet2() != null, wornWeaponId);
    }

    private void refreshConfig()
    {
        nets.refreshConfig();
        shoals.refreshConfig();
        dialogs.refreshConfig();
        closeGuard.refreshConfig();
        blockClaimOption = config.blockClaimOption();
        claimOptionText = config.claimOptionText().trim();
        blockMovingFishOut = config.blockMovingFishOut();
        deprioDoorWhileArmed = config.deprioDoorWhileArmed();
        highlightUnequippedTrident = config.highlightUnequippedTrident();
        markLowNumulite = config.markLowNumulite();
        log.debug("[config] claim={} fishMove={} doorDeprio={}", blockClaimOption, blockMovingFishOut,
            deprioDoorWhileArmed);
    }

    /**
     * Compiles the fish-move allow list, on startUp and when its key changes.
     * A new list starts with no cached decisions.
     */
    private void refreshAllowList()
    {
        allowList = new AllowList(parseGlobList(config.stillMovableItems()));
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
        boolean untaggedFirst = shoals.wantsUntaggedFirst();
        if ((!door && !untaggedFirst) || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        if (door)
        {
            deprioritizeDoor();
        }
        if (untaggedFirst)
        {
            shoals.prioritizeUntaggedShoals();
        }
    }

    void deprioritizeDoor()
    {
        // The caller has checked the setting, the area and the worn weapon; the door scan
        // allocates nothing, so menus without the door cost no reordering.
        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();

        // Entries run bottom-to-top, so the door entries go in just below the
        // first "Walk here", each group keeping its own order.
        int doors = 0;
        int walk = -1;
        for (int i = 0; i < entries.length; i++)
        {
            if (isDoorEntry(entries[i]))
            {
                doors++;
            }
            else if (walk < 0 && entries[i].getType() == MenuAction.WALK)
            {
                walk = i;
            }
        }
        if (doors == 0)
        {
            doorMovedLogged = 0;
            return;
        }
        if (walk < 0)
        {
            // No "Walk here" to place them against. Leaving the order alone beats
            // guessing an anchor.
            return;
        }
        if (doorsRightBelow(entries, walk, doors))
        {
            return;
        }

        // A stable reorder inside the array the menu handed out, which is a copy:
        // the other entries close up, then the door entries go in where the walk
        // entry lands.
        if (doorScratch.length < doors)
        {
            doorScratch = new MenuEntry[entries.length];
        }
        int door = 0;
        int rest = 0;
        int anchor = -1;
        for (int i = 0; i < entries.length; i++)
        {
            MenuEntry entry = entries[i];
            if (isDoorEntry(entry))
            {
                doorScratch[door++] = entry;
                continue;
            }
            if (i == walk)
            {
                anchor = rest;
            }
            entries[rest++] = entry;
        }
        System.arraycopy(entries, anchor, entries, anchor + doors, rest - anchor);
        System.arraycopy(doorScratch, 0, entries, anchor, doors);
        // Not kept past this menu build.
        Arrays.fill(doorScratch, 0, doors, null);

        // The menu is rebuilt every client tick while the door is hovered, so
        // log once per hover rather than per rebuild.
        if (doors != doorMovedLogged)
        {
            doorMovedLogged = doors;
            log.debug("[door] deprioritized, moved={}", doorMovedLogged);
        }
        menu.setMenuEntries(entries);
    }

    /** True when every door entry already sits in the slots right below the walk entry. */
    private static boolean doorsRightBelow(MenuEntry[] entries, int walk, int doors)
    {
        if (walk < doors)
        {
            return false;
        }
        for (int i = walk - doors; i < walk; i++)
        {
            if (!isDoorEntry(entries[i]))
            {
                return false;
            }
        }
        return true;
    }

    /** Any object action (Navigate, ...) or Examine on the plant door. */
    private static boolean isDoorEntry(MenuEntry entry)
    {
        return DOOR_CLICKS.contains(entry.getType()) && entry.getIdentifier() == DOOR_ID;
    }

    private boolean weaponEquipped()
    {
        return equippedWeaponId() > -1;
    }

    /**
     * The weapon slot item id, or -1 when the slot is empty. Cached from the
     * worn container's changes; read once per game tick in the area until the first one has been seen.
     */
    int equippedWeaponId()
    {
        // Still unknown when there is no worn container yet: an empty slot,
        // and the next game tick reads again.
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
        boolean worn = event.getContainerId() == InventoryID.WORN;
        if (worn)
        {
            readWornWeapon(event.getItemContainer());
        }
        // The highlight only draws in the zone; entering it, or turning the
        // setting on, classifies what is held by then.
        if ((worn || event.getContainerId() == InventoryID.INV) && inFishingZone && highlightUnequippedTrident)
        {
            tridentOverlay.classify(event.getItemContainer());
        }
    }

    /**
     * Classifies the inventory and worn items for the trident overlay, on the
     * client thread. The callers have checked the zone and the setting.
     */
    private void classifyHeldItems()
    {
        tridentOverlay.classify(client.getItemContainer(InventoryID.INV));
        tridentOverlay.classify(client.getItemContainer(InventoryID.WORN));
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        // Cached gates ahead of the game state read: most client ticks are
        // spent outside the area with no dialog up.
        if (!dialogs.needsUpdate() && !inArea)
        {
            closeGuard.setEscapeBlocked(false);
            return;
        }
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            dialogs.setBlockedDialogDigits(0);
            closeGuard.setEscapeBlocked(false);
            return;
        }

        // The tunnel is outside the underwater region, so this can't sit behind
        // the area gate; the chat options flag keeps it off most client ticks.
        int flags = guardAreaFlags();
        dialogs.updateDialogGuards((flags & IN_AREA) != 0, (flags & IN_FISHING_ZONE) != 0);

        if (!inArea)
        {
            closeGuard.setEscapeBlocked(false);
            return;
        }

        closeGuard.onClientTick();
    }

    /**
     * Removes the blocked entries as the menu is built, so the left-click option
     * and the opened menu both go without them.
     */
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (!inAreaForMenu()
            || (!shoals.isHideTaggedFish() && !nets.isBlockEarlyHarvest() && !blockClaimOption && !blockMovingFishOut))
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
                if (log.isDebugEnabled())
                {
                    // The harvest counts are added here, so a blocked entry builds no
                    // text with debug off.
                    log.debug("[menu] removed option={} target={} rule={}", entry.getOption(), entry.getTarget(),
                        NetTracker.EARLY_HARVEST_RULE.equals(rule) ? nets.earlyHarvestDetail() : rule);
                }
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
        if (shoals.isHiddenShoalEntry(entry))
        {
            return "hide tagged shoal";
        }
        String harvest = nets.blockedHarvestRule(entry);
        if (harvest != null)
        {
            return harvest;
        }
        if ((!blockClaimOption && !blockMovingFishOut) || closeGuard.isBankedSinceOpen() || !isDriftNetEntry(entry))
        {
            return null;
        }
        // The window check is a widget read, so it only runs for a matching entry.
        if (isBlockedClaim(entry))
        {
            return closeGuard.isDriftNetOpenThisCycle() ? "claim" : null;
        }
        if (isBlockedFishMove(entry))
        {
            return closeGuard.isDriftNetOpenThisCycle() ? "fish move" : null;
        }
        return null;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // Anything other than logged in invalidates the tracked objects; spawns
        // re-fire on the next scene load. A lost connection keeps the scene, and
        // a reconnect without a load fires no spawns, so its nets are kept. The
        // player cannot move meanwhile, so the area flags hold as well, and the
        // guards keep them in the ticks before the first game tick after it.
        if (event.getGameState() != GameState.LOGGED_IN && event.getGameState() != GameState.CONNECTION_LOST)
        {
            nets.clear();
            inArea = false;
            inFishingZone = false;
            areaFlagsStale = true;
            log.debug("[state] game state {}, tracked objects cleared", event.getGameState());
        }
        // The worn container is sent again after a login or hop. NPC indices
        // restart with the new session or world, so the tags go too; a scene
        // load needs no clear, as the NPCs it drops fire NpcDespawned.
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            wornWeaponId = WEAPON_UNKNOWN;
            shoals.clear();
            log.debug("[state] game state {}, tags and worn weapon reset", event.getGameState());
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        nets.trackNet(event.getGameObject());
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        nets.untrackNet(event.getGameObject());
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event)
    {
        shoals.onInteractingChanged(event);
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        shoals.onChatMessage(event, inArea);
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        shoals.onNpcDespawned(event, inArea);
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        boolean wasInFishingZone = inFishingZone;
        readAreaFlags();
        // The weapon is only read in the area. readLoadedScene and the worn
        // container's changes set it; this covers a container not sent yet.
        if (inArea && wornWeaponId == WEAPON_UNKNOWN)
        {
            ItemContainer worn = client.getItemContainer(InventoryID.WORN);
            readWornWeapon(worn);
            // A container that turned up without a change event still needs
            // its verdicts for the equipped check.
            if (inFishingZone && highlightUnequippedTrident)
            {
                tridentOverlay.classify(worn);
            }
        }
        if (inFishingZone && !wasInFishingZone && highlightUnequippedTrident)
        {
            classifyHeldItems();
        }
        nets.onGameTick(inArea);
        shoals.onGameTick(inArea);
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        nets.onVarbitChanged(event, inArea);
    }

    boolean isHighlightUnequippedTrident()
    {
        return highlightUnequippedTrident;
    }

    boolean isMarkLowNumulite()
    {
        return markLowNumulite;
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
            closeGuard.setWindowLoaded(true);
            closeGuard.arm();
        }
        else if (event.getGroupId() == InterfaceID.CHATMENU)
        {
            dialogs.onChatOptionsLoaded();
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        if (event.getGroupId() == InterfaceID.CHATMENU)
        {
            dialogs.onChatOptionsClosed();
        }
        else if (event.getGroupId() == InterfaceID.FOSSIL_DRIFTNET)
        {
            // Second arming path beside the window load: a closed window means
            // the next one holds a fresh catch. The bank confirmation and the
            // leftover grid stay inside the open window, so a bank holds. The
            // loaded flag is read back from the widget a tick later rather than
            // cleared, so a close fired around a reopen cannot leave it false.
            clientThread.invokeLater(closeGuard::readWindowLoaded);
            closeGuard.arm();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (BetterDriftNetConfig.GROUP.equals(event.getGroup()))
        {
            refreshConfig();
            if (BetterDriftNetConfig.STILL_MOVABLE_ITEMS_KEY.equals(event.getKey()))
            {
                refreshAllowList();
            }
            boolean weaponNames = CHASING_WEAPONS_KEY.equals(event.getKey());
            if (weaponNames)
            {
                tridentOverlay.refreshPatterns();
                tridentOverlay.clearTridentCache();
            }
            if (weaponNames || HIGHLIGHT_TRIDENT_KEY.equals(event.getKey()))
            {
                // Rendering only reads the verdicts, so the held items are
                // classified here when the highlight can draw.
                clientThread.invokeLater(() ->
                {
                    if (started && inFishingZone && highlightUnequippedTrident)
                    {
                        classifyHeldItems();
                    }
                });
            }
            if (HIDE_TAGGED_KEY.equals(event.getKey()) || MARKER_KEY.equals(event.getKey()))
            {
                // Collected per game tick; this draws a setting turned on
                // within a frame instead.
                clientThread.invokeLater(() ->
                {
                    if (started)
                    {
                        shoals.refreshTaggedShoals(inArea);
                    }
                });
            }
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (closeGuard.disarmOnConfirm(event) || dialogs.consumeTunnelClick(event))
        {
            return;
        }

        int flags = guardAreaFlags();
        if ((flags & IN_AREA) == 0)
        {
            return;
        }

        // A menu opened before its shoal was tagged still holds the old
        // entries, as the menu is not built again while it stays open.
        if (shoals.isHiddenShoalEntry(event.getMenuEntry()))
        {
            event.consume();
            log.debug("[menu] blocked click on tagged shoal option={}", event.getMenuOption());
            return;
        }

        if (!dialogs.consumeTridentClick(event, (flags & IN_FISHING_ZONE) != 0))
        {
            closeGuard.consumeIfClosingClick(event);
        }
    }

    /**
     * The area and zone flags for the click and dialog guards: the cached ones,
     * or read live while they await the first game tick after a scene load, so
     * a guard holds in that tick too. The cached fields are left alone.
     */
    private int guardAreaFlags()
    {
        if (!areaFlagsStale)
        {
            return (inArea ? IN_AREA : 0) | (inFishingZone ? IN_FISHING_ZONE : 0);
        }
        return liveAreaFlags();
    }

    /** Maps the player point into the cached area and zone flags. */
    private void readAreaFlags()
    {
        int flags = liveAreaFlags();
        inArea = (flags & IN_AREA) != 0;
        inFishingZone = (flags & IN_FISHING_ZONE) != 0;
        areaFlagsStale = false;
    }

    /** The area and zone flags from the player point, read now. Writes nothing. */
    private int liveAreaFlags()
    {
        WorldPoint point = DriftNetArea.playerPoint(client);
        if (!DriftNetArea.inDriftNetArea(client, point))
        {
            return 0;
        }
        // The zone lies inside the area, so outside it the zone check is skipped.
        return IN_AREA | (DriftNetArea.inDriftNetFishingZone(point) ? IN_FISHING_ZONE : 0);
    }

    /**
     * The area flag for the menu filters: the cached one, or read live while
     * the flags await the first game tick after a scene load. In the area the
     * cached flag answers alone, so a menu build there costs one field read.
     */
    private boolean inAreaForMenu()
    {
        return inArea || areaFlagsStale && (guardAreaFlags() & IN_AREA) != 0;
    }

    // The number keys pick a dialog line without going through
    // MenuOptionClicked. The pressed key and the typed key arrive as separate
    // events, so consuming one does not suppress the other and both are taken.
    // These run on the AWT thread, so they only read the digits the client
    // tick recorded and never touch client state.
    @Override
    public void keyPressed(KeyEvent e)
    {
        dialogs.consumeIfBlockedDialogDigit(e);
        consumeIfBlockedEscape(e);
    }

    @Override
    public void keyTyped(KeyEvent e)
    {
        dialogs.consumeIfBlockedDialogDigit(e);
        consumeIfBlockedEscape(e);
    }

    private void consumeIfBlockedEscape(KeyEvent e)
    {
        if (closeGuard.consumeIfBlockedEscape(e))
        {
            clientThread.invokeLater(() ->
            {
                if (started)
                {
                    closeGuard.sendBlockMessage();
                }
            });
        }
    }

    @Override
    public void keyReleased(KeyEvent e)
    {
    }

    /** Whether a menu entry belongs to the drift net interface itself. */
    private static boolean isDriftNetEntry(MenuEntry entry)
    {
        return WidgetUtil.componentToInterface(entry.getParam1()) == InterfaceID.FOSSIL_DRIFTNET;
    }

    /** The caller has checked the entry is on the window and the catch is not banked. */
    private boolean isBlockedClaim(MenuEntry entry)
    {
        // Scoped to the window's own entries: the option text is user-configurable, and
        // a common word would otherwise strip matching entries elsewhere on screen.
        String blocked = claimOptionText;
        return blockClaimOption && !blocked.isEmpty()
            && blocked.equalsIgnoreCase(Text.removeTags(entry.getOption()));
    }

    /**
     * The caller has checked the entry is on the window and the catch is not
     * banked: once it is, what is left is the unbankable remainder, so moving
     * it out is the only way to keep it.
     */
    boolean isBlockedFishMove(MenuEntry entry)
    {
        if (!blockMovingFishOut || !MOVE_TO_INVENTORY.equalsIgnoreCase(Text.removeTags(entry.getOption())))
        {
            return false;
        }

        // The menu is rebuilt every client tick with the same items, so the
        // decision is kept per target text.
        AllowList list = allowList;
        String target = entry.getTarget();
        Boolean allowed = list.allowed.get(target);
        if (allowed == null)
        {
            allowed = list.matches(Text.removeTags(target).toLowerCase(Locale.ROOT));
            list.allowed.put(target, allowed);
        }
        return !allowed;
    }

    /**
     * The compiled fish-move allow list and the decisions made against it, by
     * raw target text. Replaced whole when the list is rebuilt, so the menu
     * build never reads a decision made under another list; the decision map
     * is touched on the client thread only.
     */
    private static final class AllowList
    {
        private final List<Pattern> patterns;
        private final Map<String, Boolean> allowed = new HashMap<>();

        AllowList(List<Pattern> patterns)
        {
            this.patterns = patterns;
        }

        boolean matches(String target)
        {
            return matchesAny(patterns, target);
        }
    }

    /** True when any pattern matches the whole lowercased text. Shared with the trident overlay. */
    static boolean matchesAny(List<Pattern> patterns, String text)
    {
        for (Pattern pattern : patterns)
        {
            if (pattern.matcher(text).matches())
            {
                return true;
            }
        }
        return false;
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
