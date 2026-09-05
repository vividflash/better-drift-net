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
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
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
 * below. The door deprio is scoped by its object id instead, since it is
 * approached from outside the region.
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

    private static final int SHOAL_UNTAGGED = 1;
    private static final int SHOAL_TAGGED = 2;

    // A prod is what tags a shoal, and nothing announces the tag ending, so tags are
    // held per NPC index and expire on a timer as well as on despawn. The shoal's own
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

    // The two net objects, tracked so the overlay can draw their clickbox.
    private GameObject net1Object;
    private GameObject net2Object;

    private final Map<Integer, Integer> taggedUntilTick = new HashMap<>();
    private NPC lastShoalInteraction;

    private boolean bankedSinceOpen;
    private boolean interfaceWasOpen;
    private boolean blockMessageSent;

    // Rebuilt only when the allow-list config text changes.
    private String allowListSource;
    private List<Pattern> allowListPatterns = List.of();

    public void startUp()
    {
        renderCallbackManager.register(this);
        bankedSinceOpen = false;
        interfaceWasOpen = false;
        blockMessageSent = false;
        taggedUntilTick.clear();
        lastShoalInteraction = null;
        net1Object = null;
        net2Object = null;
        eventBus.register(this);
        overlayManager.add(sceneOverlay);
        overlayManager.add(tridentOverlay);
        overlayManager.add(numuliteOverlay);
        keyManager.registerKeyListener(this);
    }

    public void shutDown()
    {
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
        lastShoalInteraction = null;
        net1Object = null;
        net2Object = null;
        allowListSource = null;
        allowListPatterns = List.of();
    }

    /**
     * Sinks the plant door's "Navigate" (and Examine) below "Walk here" while
     * a weapon is worn; it can't be swum through armed. Runs after
     * RuneLite's own menu sorting.
     */
    @Subscribe(priority = -1)
    public void onPostMenuSort(PostMenuSort event)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        deprioritizeDoor();
        prioritizeUntaggedShoals();
    }

    /**
     * Puts untagged shoals above tagged ones where their entries overlap, so a
     * second left-click tags the other shoal instead of re-clicking the first.
     * Only the shoal entries move; every other entry keeps its slot.
     */
    private void prioritizeUntaggedShoals()
    {
        if (!config.prioritizeUntaggedFish())
        {
            return;
        }

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
            menu.setMenuEntries(reordered);
        }
    }

    /** Keeps tagged shoals out of the render while their tag holds. */
    @Override
    public boolean addEntity(Renderable renderable, boolean drawingUI)
    {
        if (!config.hideTaggedFish() || !(renderable instanceof NPC))
        {
            return true;
        }
        return !isTaggedShoal((NPC) renderable);
    }

    /** True for a fish shoal prodded recently enough that its tag has not expired. */
    boolean isTaggedShoal(NPC npc)
    {
        if (npc.getId() != NpcID.FOSSIL_FISH_SHOAL)
        {
            return false;
        }
        Integer expiry = taggedUntilTick.get(npc.getIndex());
        return expiry != null && expiry > client.getTickCount();
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
        // No area check: the door object id exists only here, and the approach side
        // is outside the underwater region, which is where the deprio has to work.
        if (!config.deprioDoorWhileArmed() || !weaponEquipped())
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
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
            menu.setMenuEntries(reordered);
        }
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
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        if (worn == null)
        {
            return false;
        }
        // An empty equipment slot is an Item with id -1, not a null.
        Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        return weapon != null && weapon.getId() > -1;
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (config.tunnelDialogGuard())
        {
            highlightInstanceEntryDialog();
        }

        if (!inDriftNetArea())
        {
            return;
        }

        // The window going from closed to open means a fresh unbanked catch.
        boolean interfaceOpen = isDriftNetOpen();
        if (interfaceOpen && !interfaceWasOpen)
        {
            armCloseGuard();
        }
        interfaceWasOpen = interfaceOpen;

        if (config.tridentWarningGuard() && inDriftNetFishingZone())
        {
            highlightTridentDialog();
        }

        if (client.isMenuOpen())
        {
            return;
        }

        // The claim and fish-move guards act on the open reward interface; the
        // early-harvest gate acts on the world "Harvest" object click while the
        // interface is still closed, so it isn't gated on the window being open.
        // Both interface guards lift on the same signal as the close guard: once the
        // catch is banked, what is left is the unbankable remainder and taking it to
        // inventory is the only way to keep it.
        boolean claimGuard = config.blockClaimOption() && interfaceOpen && !bankedSinceOpen;
        boolean fishGuard = config.blockMovingFishOut() && interfaceOpen;
        boolean minCatchGuard = config.blockEarlyHarvest();
        boolean hideTagged = config.hideTaggedFish();
        if (!claimGuard && !fishGuard && !minCatchGuard && !hideTagged)
        {
            return;
        }

        String blocked = claimGuard ? config.claimOptionText().trim() : "";
        List<Pattern> allowList = fishGuard ? parseAllowList() : List.of();
        int minCatch = minCatchGuard ? config.minFishToHarvest() : 0;

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        MenuEntry[] filtered = Arrays.stream(entries)
            .filter(entry -> !isBlockedClaim(entry, blocked)
                && !isBlockedFishMove(entry, fishGuard, allowList)
                && !isBlockedEarlyHarvest(entry, minCatchGuard, minCatch)
                && !(hideTagged && shoalState(entry) == SHOAL_TAGGED))
            .toArray(MenuEntry[]::new);

        if (filtered.length != entries.length)
        {
            menu.setMenuEntries(filtered);
        }
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
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        GameObject object = event.getGameObject();
        if (object.getId() == ObjectID.FOSSIL_DRIFT_NET1_MULTI)
        {
            net1Object = object;
        }
        else if (object.getId() == ObjectID.FOSSIL_DRIFT_NET2_MULTI)
        {
            net2Object = object;
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
            lastShoalInteraction = (NPC) target;
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (lastShoalInteraction == null
            || !Text.removeTags(event.getMessage()).toLowerCase(Locale.ROOT).contains(PROD_MESSAGE))
        {
            return;
        }
        taggedUntilTick.put(lastShoalInteraction.getIndex(),
            client.getTickCount() + config.tagTimeoutTicks());
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        taggedUntilTick.remove(event.getNpc().getIndex());
        if (event.getNpc() == lastShoalInteraction)
        {
            lastShoalInteraction = null;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        int now = client.getTickCount();
        taggedUntilTick.values().removeIf(expiry -> expiry <= now);
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == InterfaceID.FOSSIL_DRIFTNET)
        {
            armCloseGuard();
        }
    }

    private void armCloseGuard()
    {
        bankedSinceOpen = false;
        blockMessageSent = false;
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
            bankedSinceOpen = true;
            return;
        }

        if (config.tunnelDialogGuard() && isDontEnterClick(event))
        {
            event.consume();
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

        if (config.tridentWarningGuard() && inDriftNetFishingZone() && isPlayItSafeClick(event))
        {
            event.consume();
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

        // Everything on the window itself stays clickable. "Close" is matched on
        // option text alone, so a Close on any interface is blocked while armed.
        if (!SCENE_CLICKS.contains(event.getMenuAction())
            && !"Close".equalsIgnoreCase(Text.removeTags(event.getMenuOption())))
        {
            return;
        }

        event.consume();
        if (config.showBlockMessages() && !blockMessageSent)
        {
            blockMessageSent = true;
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                "[Safety] Bank the catch first (Bank all + confirm).", null);
        }
    }

    // The number keys pick a dialog line without going through
    // MenuOptionClicked. The pressed key and the typed key arrive as separate
    // events, so consuming one does not suppress the other and both are taken.
    @Override
    public void keyPressed(KeyEvent e)
    {
        consumeIfBlockedDialogDigit(e);
    }

    @Override
    public void keyTyped(KeyEvent e)
    {
        consumeIfBlockedDialogDigit(e);
    }

    @Override
    public void keyReleased(KeyEvent e)
    {
    }

    private void consumeIfBlockedDialogDigit(KeyEvent e)
    {
        if (!config.tridentWarningGuard() && !config.tunnelDialogGuard())
        {
            return;
        }

        int digit = digitOf(e);
        if (digit > 0 && (isBlockedTridentDigit(digit) || isBlockedTunnelDigit(digit)))
        {
            e.consume();
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
     * True when the given 1-based digit currently selects "Play it safe." on
     * the trident dialog. The dialog title takes a child slot of its own, so
     * the offset comes from locating the first child that is one of the two
     * option lines rather than from a fixed index.
     */
    private boolean isBlockedTridentDigit(int digit)
    {
        if (!config.tridentWarningGuard() || !inDriftNetArea() || !inDriftNetFishingZone())
        {
            return false;
        }

        Widget[] lines = dialogOptionLines();
        if (lines == null || !containsPlayItSafe(lines))
        {
            return false;
        }

        int firstOptionIndex = firstTridentOptionIndex(lines);
        if (firstOptionIndex < 0)
        {
            return false;
        }

        int index = firstOptionIndex + digit - 1;
        if (index < 0 || index >= lines.length || lines[index] == null)
        {
            return false;
        }

        String text = lines[index].getText();
        return text != null && PLAY_IT_SAFE.equalsIgnoreCase(Text.removeTags(text));
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

    /**
     * True when the given 1-based digit currently selects "Don't enter." on
     * the tunnel's already-paid dialog. The dialog title takes a child slot
     * of its own, so the offset comes from locating the first child that is
     * one of the two option lines rather than from a fixed index.
     */
    private boolean isBlockedTunnelDigit(int digit)
    {
        if (!config.tunnelDialogGuard())
        {
            return false;
        }

        Widget[] lines = dialogOptionLines();
        if (lines == null || !isInstanceEntryDialog(lines))
        {
            return false;
        }

        int firstOptionIndex = firstTunnelOptionIndex(lines);
        if (firstOptionIndex < 0)
        {
            return false;
        }

        int index = firstOptionIndex + digit - 1;
        if (index < 0 || index >= lines.length || lines[index] == null)
        {
            return false;
        }

        String text = lines[index].getText();
        return text != null && DONT_ENTER.equalsIgnoreCase(Text.removeTags(text));
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

    private boolean isBlockedClaim(MenuEntry entry, String blocked)
    {
        // Scoped to the window's own entries: the option text is user-configurable, and
        // a common word would otherwise strip matching entries elsewhere on screen.
        return !blocked.isEmpty() && isDriftNetEntry(entry)
            && blocked.equalsIgnoreCase(Text.removeTags(entry.getOption()));
    }

    private boolean isBlockedFishMove(MenuEntry entry, boolean fishGuard, List<Pattern> allowList)
    {
        // Once the catch is banked, what is left is the unbankable remainder, so
        // moving it out is the only way to keep it.
        if (!fishGuard || bankedSinceOpen || !isDriftNetEntry(entry)
            || !MOVE_TO_INVENTORY.equalsIgnoreCase(Text.removeTags(entry.getOption())))
        {
            return false;
        }

        String target = Text.removeTags(entry.getTarget()).toLowerCase(Locale.ROOT);
        for (Pattern allowed : allowList)
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
    private boolean isBlockedEarlyHarvest(MenuEntry entry, boolean minCatchGuard, int minCatch)
    {
        // The object-click check keeps param0/param1 meaningful as scene coords below.
        if (!minCatchGuard || !OBJECT_CLICKS.contains(entry.getType())
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
     * the harvest allowed, so the guard never blocks on a guess.
     */
    private int harvestCatchCount(MenuEntry entry)
    {
        LocalPoint scene = LocalPoint.fromScene(entry.getParam0(), entry.getParam1(),
            client.getTopLevelWorldView());
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
        int count = client.getVarbitValue(catchVarbit);
        return count >= 0 && count <= NET_CAPACITY ? count : -1;
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
     * warning dialog. The colour is written onto the widget and not restored.
     */
    private void highlightTridentDialog()
    {
        Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        if (options == null)
        {
            return;
        }
        Widget[] lines = options.getDynamicChildren();
        if (lines == null || !containsPlayItSafe(lines))
        {
            return;
        }
        for (Widget line : lines)
        {
            String text = line.getText();
            if (text != null
                && Text.removeTags(text).toLowerCase(Locale.ROOT).contains(WIELD_ANYWAY_MARKER))
            {
                line.setTextColor(HIGHLIGHT_GREEN);
            }
        }
    }

    /**
     * Green-highlights "Enter instance." on the tunnel's already-paid dialog. The
     * colour is written onto the widget and not restored.
     */
    private void highlightInstanceEntryDialog()
    {
        Widget[] lines = dialogOptionLines();
        if (lines == null || !isInstanceEntryDialog(lines))
        {
            return;
        }
        for (Widget line : lines)
        {
            String text = line.getText();
            if (text != null && ENTER_INSTANCE.equalsIgnoreCase(Text.removeTags(text)))
            {
                line.setTextColor(HIGHLIGHT_GREEN);
            }
        }
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
        if (lines == null || index < 0 || index >= lines.length || !isInstanceEntryDialog(lines))
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
            String text = line.getText();
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
            String text = line.getText();
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
        if (lines == null || index < 0 || index >= lines.length)
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

    /** Shared with the scene overlay. */
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

    /** Shared with the trident overlay. */
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

    private List<Pattern> parseAllowList()
    {
        String csv = config.stillMovableItems();
        if (!csv.equals(allowListSource))
        {
            allowListSource = csv;
            allowListPatterns = parseGlobList(csv);
        }
        return allowListPatterns;
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
