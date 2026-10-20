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
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.util.Text;

/**
 * Prod tags on fish shoals: which shoals are tagged, hiding them from the
 * render and the menu, the marker list for the scene overlay, and putting
 * untagged shoals first. Driven by {@link DriftNetFeature}'s event handlers.
 */
@Slf4j
@Singleton
public class ShoalTracker implements RenderCallback
{
    private static final int SHOAL_UNTAGGED = 1;
    private static final int SHOAL_TAGGED = 2;

    // A prod is what tags a shoal, and nothing announces the tag ending, so tags are
    // held per NPC index and end on a timer, on despawn, on reaching a net, or on leaving
    // the area. The shoal's own animation is no use here: it tracks whether the fish is moving, not whether it
    // has been prodded.
    private static final String PROD_MESSAGE = "prod at the shoal";

    // Ticks a tagged shoal keeps its tag after reaching an entry tile. Caught
    // shoals despawned 0 to 3 s after reaching it in game (2026-10-17).
    private static final int ENTRY_GRACE_TICKS = 5;

    @Inject
    private Client client;

    @Inject
    private BetterDriftNetConfig config;

    // Tag expiry tick per NPC index, 0 for no tag, and the tagged indices. Both
    // are only touched on the client thread, which also renders and builds menus.
    private int[] tagExpiry = new int[0];
    private int[] taggedIndices = new int[8];
    private int tagCount;
    private NPC lastShoalInteraction;

    // The tagged shoals the scene overlay marks, collected once per game tick.
    private final List<NPC> taggedShoals = new ArrayList<>();
    private final List<NPC> taggedShoalsView = Collections.unmodifiableList(taggedShoals);

    // Reused by the untagged-first reorder: each entry's shoal state, and the
    // shoal entries in their new order. Client thread only.
    private int[] shoalStates = new int[0];
    private MenuEntry[] shoalOrder = new MenuEntry[0];

    // Config values read on per-tick and per-frame paths. Same threading as
    // the feature's cached values.
    private volatile boolean hideTaggedFish;
    private volatile boolean prioritizeUntaggedFish;
    private volatile TaggedFishMarker taggedFishMarker = TaggedFishMarker.OFF;
    private volatile Color taggedFishColour;

    void refreshConfig()
    {
        hideTaggedFish = config.hideTaggedFish();
        prioritizeUntaggedFish = config.prioritizeUntaggedFish();
        taggedFishMarker = config.taggedFishMarker();
        taggedFishColour = config.taggedFishColour();
        log.debug("[config] hideTagged={} prioritizeUntagged={} marker={}",
            hideTaggedFish, prioritizeUntaggedFish, taggedFishMarker);
    }

    /**
     * Drops every tag, the marker list and the last shoal interaction. Called
     * by startUp before the render callback and the event subscribers are
     * registered, and on the client thread otherwise, so no game tick or
     * render frame is using them meanwhile.
     */
    void clear()
    {
        for (int i = 0; i < tagCount; i++)
        {
            tagExpiry[taggedIndices[i]] = 0;
        }
        tagCount = 0;
        taggedShoals.clear();
        lastShoalInteraction = null;
    }

    private void addTag(int index, int expiry)
    {
        if (index >= tagExpiry.length)
        {
            tagExpiry = Arrays.copyOf(tagExpiry, Math.max(index + 1, tagExpiry.length * 2));
        }
        if (tagExpiry[index] == 0)
        {
            if (tagCount == taggedIndices.length)
            {
                taggedIndices = Arrays.copyOf(taggedIndices, tagCount * 2);
            }
            taggedIndices[tagCount++] = index;
        }
        // 0 marks no tag, so an expiry is never stored as 0.
        tagExpiry[index] = Math.max(expiry, 1);
    }

    /** Drops the tag at the position in the tagged list; the last tag takes its place. */
    private void removeTagAt(int position)
    {
        tagExpiry[taggedIndices[position]] = 0;
        taggedIndices[position] = taggedIndices[--tagCount];
    }

    private boolean removeTag(int index)
    {
        if (index >= tagExpiry.length || tagExpiry[index] == 0)
        {
            return false;
        }
        for (int i = 0; i < tagCount; i++)
        {
            if (taggedIndices[i] == index)
            {
                removeTagAt(i);
                break;
            }
        }
        return true;
    }

    /** Keeps tagged shoals out of the render while their tag holds. */
    @Override
    public boolean addEntity(Renderable renderable, boolean drawingUI)
    {
        if (tagCount == 0 || !hideTaggedFish || !(renderable instanceof NPC))
        {
            return true;
        }
        return !isTaggedShoal((NPC) renderable);
    }

    /** True for a fish shoal prodded recently enough that its tag has not expired. */
    private boolean isTaggedShoal(NPC npc)
    {
        return npc.getId() == NpcID.FOSSIL_FISH_SHOAL && isTagged(npc);
    }

    /** True when the NPC's index holds a tag; the caller has checked it is a shoal. */
    private boolean isTagged(NPC npc)
    {
        // onGameTick drops expired tags, so a tag still in the map holds.
        int index = npc.getIndex();
        return index < tagExpiry.length && tagExpiry[index] != 0;
    }

    /** SHOAL_TAGGED, SHOAL_UNTAGGED, or 0 when the entry is not a fish shoal. */
    private int shoalState(MenuEntry entry)
    {
        NPC npc = entry.getNpc();
        if (npc == null || npc.getId() != NpcID.FOSSIL_FISH_SHOAL)
        {
            return 0;
        }
        return isTagged(npc) ? SHOAL_TAGGED : SHOAL_UNTAGGED;
    }

    /** True for a tagged shoal's menu entry while tagged shoals are hidden. */
    boolean isHiddenShoalEntry(MenuEntry entry)
    {
        return hideTaggedFish && tagCount != 0 && shoalState(entry) == SHOAL_TAGGED;
    }

    /** True when untagged shoals go first and some shoal is tagged. */
    boolean wantsUntaggedFirst()
    {
        return prioritizeUntaggedFish && tagCount != 0;
    }

    /**
     * Puts untagged shoals above tagged ones where their entries overlap, so a
     * second left-click tags the other shoal instead of re-clicking the first.
     * Only the shoal entries move; every other entry keeps its slot.
     */
    void prioritizeUntaggedShoals()
    {
        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();

        // Most menus hold only one kind, so check before reordering anything.
        // The states are kept, so each entry is looked at once. Examine is
        // not a click on the shoal and keeps its slot.
        if (shoalStates.length < entries.length)
        {
            shoalStates = new int[entries.length];
        }
        int[] states = shoalStates;
        boolean hasTagged = false;
        boolean hasUntagged = false;
        for (int i = 0; i < entries.length; i++)
        {
            int state = entries[i].getType() == MenuAction.EXAMINE_NPC ? 0 : shoalState(entries[i]);
            states[i] = state;
            hasTagged |= state == SHOAL_TAGGED;
            hasUntagged |= state == SHOAL_UNTAGGED;
        }
        if (!hasTagged || !hasUntagged)
        {
            return;
        }

        // The shoal entries in their new order: tagged first, then untagged,
        // each kind keeping its own order.
        if (shoalOrder.length < entries.length)
        {
            shoalOrder = new MenuEntry[entries.length];
        }
        MenuEntry[] order = shoalOrder;
        int count = 0;
        for (int i = 0; i < entries.length; i++)
        {
            if (states[i] == SHOAL_TAGGED)
            {
                order[count++] = entries[i];
            }
        }
        for (int i = 0; i < entries.length; i++)
        {
            if (states[i] == SHOAL_UNTAGGED)
            {
                order[count++] = entries[i];
            }
        }

        // Entries run bottom-to-top, so the tagged ones take the lower slots.
        // Written back into the array the menu handed out, which is a copy.
        int slot = 0;
        int moved = 0;
        boolean changed = false;
        for (int at = 0; at < entries.length; at++)
        {
            if (states[at] == 0)
            {
                continue;
            }
            MenuEntry entry = order[slot];
            // Not kept past this menu build.
            order[slot++] = null;
            if (entries[at] != entry)
            {
                moved++;
                changed |= !entry.equals(entries[at]);
            }
            entries[at] = entry;
        }
        if (changed)
        {
            log.debug("[shoal] reordered, moved={}", moved);
            menu.setMenuEntries(entries);
        }
    }

    /**
     * Takes the shoal the local player already interacts with, for a plugin
     * enabled mid-interaction: no InteractingChanged reports it.
     */
    void readInteraction()
    {
        Player local = client.getLocalPlayer();
        Actor target = local == null ? null : local.getInteracting();
        if (target instanceof NPC && ((NPC) target).getId() == NpcID.FOSSIL_FISH_SHOAL)
        {
            lastShoalInteraction = (NPC) target;
            log.debug("[tag] already interacting with shoal index={}", lastShoalInteraction.getIndex());
        }
    }

    void onInteractingChanged(InteractingChanged event)
    {
        Actor target = event.getTarget();
        if (target instanceof NPC && ((NPC) target).getId() == NpcID.FOSSIL_FISH_SHOAL
            && event.getSource() == client.getLocalPlayer())
        {
            if (lastShoalInteraction != target)
            {
                log.debug("[tag] interacting with shoal index={}", ((NPC) target).getIndex());
            }
            lastShoalInteraction = (NPC) target;
        }
    }

    void onChatMessage(ChatMessage event, boolean inArea)
    {
        if (!inArea)
        {
            return;
        }
        if (lastShoalInteraction == null)
        {
            if (log.isDebugEnabled() && isProdMessage(event.getType(), event.getMessage()))
            {
                log.debug("[tag] prod message but no shoal interaction, nothing tagged");
            }
            return;
        }
        if (!isProdMessage(event.getType(), event.getMessage()))
        {
            return;
        }
        int expiry = client.getTickCount() + config.tagTimeoutTicks();
        addTag(lastShoalInteraction.getIndex(), expiry);
        if (log.isDebugEnabled())
        {
            Player local = client.getLocalPlayer();
            log.debug("[tag] tagged index={} npcAt={} playerAt={} expiresTick={}", lastShoalInteraction.getIndex(),
                lastShoalInteraction.getWorldLocation(), local == null ? null : local.getWorldLocation(), expiry);
        }
        refreshTaggedShoals(inArea);
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

    void onNpcDespawned(NpcDespawned event, boolean inArea)
    {
        boolean tagDropped = tagCount != 0 && removeTag(event.getNpc().getIndex());
        // Every shoal, tagged or not: a caught one despawns on its net's entry tile.
        if (inArea && log.isDebugEnabled() && event.getNpc().getId() == NpcID.FOSSIL_FISH_SHOAL)
        {
            log.debug("[shoal] despawned index={} at={} tagDropped={}", event.getNpc().getIndex(),
                WorldPoint.fromLocalInstance(client, event.getNpc().getLocalLocation()), tagDropped);
        }
        if (tagDropped && !taggedShoals.isEmpty())
        {
            taggedShoals.remove(event.getNpc());
        }
        if (event.getNpc() == lastShoalInteraction)
        {
            lastShoalInteraction = null;
        }
    }

    void onGameTick(boolean inArea)
    {
        if (!inArea)
        {
            // Tags do not survive leaving the area. The marker list only fills
            // with tags held in the area, so it is empty once they are gone.
            if (tagCount != 0 || lastShoalInteraction != null)
            {
                log.debug("[tag] left the area, cleared {}", tagCount);
                clear();
            }
            return;
        }
        if (!taggedShoals.isEmpty())
        {
            taggedShoals.clear();
        }
        if (tagCount == 0)
        {
            return;
        }
        int now = client.getTickCount();
        int before = tagCount;
        for (int i = 0; i < tagCount;)
        {
            if (tagExpiry[taggedIndices[i]] <= now)
            {
                removeTagAt(i);
            }
            else
            {
                i++;
            }
        }
        if (tagCount != before)
        {
            log.debug("[tag] expired {}, remaining={}", before - tagCount, tagCount);
        }
        if (tagCount == 0)
        {
            return;
        }
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return;
        }
        // One lookup per tagged shoal serves both the net check and the marker list.
        boolean collect = hideTaggedFish && taggedFishMarker != TaggedFishMarker.OFF;
        int endsAt = now + ENTRY_GRACE_TICKS;
        IndexedObjectSet<? extends NPC> npcs = worldView.npcs();
        for (int i = 0; i < tagCount; i++)
        {
            int index = taggedIndices[i];
            NPC npc = npcs.byIndex(index);
            if (npc == null)
            {
                continue;
            }
            dropTagAtNet(index, npc, endsAt);
            if (collect && npc.getId() == NpcID.FOSSIL_FISH_SHOAL)
            {
                taggedShoals.add(npc);
            }
        }
    }

    /**
     * A tagged shoal that reaches a net's entry tile loses its prod either way: a net
     * taking fish catches it (and it despawns), one that is unset or full
     * turns it away unprodded, free to prod again. The tag is cut short rather
     * than dropped: a shoal being caught can sit on the tile a tick or two
     * first, and should stay hidden until it despawns.
     */
    private void dropTagAtNet(int index, NPC npc, int endsAt)
    {
        if (tagExpiry[index] <= endsAt)
        {
            return;
        }
        WorldPoint wp = WorldPoint.fromLocalInstance(client, npc.getLocalLocation());
        if (wp == null)
        {
            return;
        }
        boolean atNet1 = DriftNetArea.NET1_ENTRY.equals(wp);
        if (atNet1 || DriftNetArea.NET2_ENTRY.equals(wp))
        {
            tagExpiry[index] = endsAt;
            if (log.isDebugEnabled())
            {
                log.debug("[tag] shoal reached net{}, tag ends tick={} index={} at={}", atNet1 ? 1 : 2, endsAt, npc.getIndex(), wp);
            }
        }
    }

    /** Collects the tagged shoals the scene overlay marks, so it does not scan NPCs per frame. */
    void refreshTaggedShoals(boolean inArea)
    {
        if (!taggedShoals.isEmpty())
        {
            taggedShoals.clear();
        }
        // The overlay draws nothing unless both are set, so nothing is collected
        // either. Turning one on refreshes the list at once (onConfigChanged);
        // turning one off is checked by the overlay per frame.
        if (tagCount == 0 || !inArea || !hideTaggedFish || taggedFishMarker == TaggedFishMarker.OFF)
        {
            return;
        }
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return;
        }
        IndexedObjectSet<? extends NPC> npcs = worldView.npcs();
        for (int i = 0; i < tagCount; i++)
        {
            NPC npc = npcs.byIndex(taggedIndices[i]);
            if (npc != null && npc.getId() == NpcID.FOSSIL_FISH_SHOAL)
            {
                taggedShoals.add(npc);
            }
        }
    }

    /** The tagged shoals in the area, as of the last game tick or prod message. Read-only. */
    List<NPC> getTaggedShoals()
    {
        return taggedShoalsView;
    }

    TaggedFishMarker getTaggedFishMarker()
    {
        return taggedFishMarker;
    }

    boolean isHideTaggedFish()
    {
        return hideTaggedFish;
    }

    Color getTaggedFishColour()
    {
        return taggedFishColour;
    }
}
