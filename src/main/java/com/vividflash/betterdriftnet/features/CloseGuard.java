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
import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.util.Text;

/**
 * The bank-before-close guard, an armed/disarmed state machine: the drift
 * net interface loading or closing (re)arms it, and confirming the bank or a bin
 * destroy disarms it. While armed it consumes the world and window-close
 * clicks, and Escape, that would discard the catch. Driven by
 * {@link DriftNetFeature}'s event handlers and key listener.
 */
@Slf4j
@Singleton
public class CloseGuard
{
    /** Scene clicks that close the drift net interface and dump the catch. */
    private static final Set<MenuAction> SCENE_CLICKS = sceneClicks();

    private static Set<MenuAction> sceneClicks()
    {
        Set<MenuAction> clicks = EnumSet.copyOf(NetTracker.OBJECT_CLICKS);
        clicks.addAll(EnumSet.of(
            MenuAction.WALK,
                MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
            MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION,
            MenuAction.NPC_FIFTH_OPTION,
            MenuAction.GROUND_ITEM_FIRST_OPTION, MenuAction.GROUND_ITEM_SECOND_OPTION,
            MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
            MenuAction.GROUND_ITEM_FIFTH_OPTION,
            MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
            MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION,
            MenuAction.PLAYER_FIFTH_OPTION, MenuAction.PLAYER_SIXTH_OPTION,
            MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION));
        return clicks;
    }

    @Inject
    private Client client;

    @Inject
    private BetterDriftNetConfig config;

    // Set while the close guard is armed with the drift net window open, so
    // Escape (which closes it without a menu click) can be taken. Written on
    // the client thread each client tick, read by the key listener on the
    // AWT thread, which must not touch client state itself.
    private volatile boolean escapeBlocked;

    // Read on the client tick and click paths. Same threading as the
    // feature's cached values.
    private volatile boolean bankBeforeClose;

    private boolean bankedSinceOpen;

    // True while the drift net interface is loaded, so the window is read only
    // then. Separate from arming. Client thread only.
    private boolean windowLoaded;

    // The drift net window state last read for the menu build, and the client
    // cycle it was read in. Client thread only.
    private int openReadCycle = -1;
    private boolean openRead;

    private boolean blockMessageSent;

    void refreshConfig()
    {
        bankBeforeClose = config.bankBeforeClose();
        log.debug("[config] bankBeforeClose={}", bankBeforeClose);
    }

    void reset()
    {
        bankedSinceOpen = false;
        blockMessageSent = false;
        escapeBlocked = false;
        windowLoaded = false;
    }

    void setWindowLoaded(boolean loaded)
    {
        windowLoaded = loaded;
    }

    /** Reads whether the window is already up, for a plugin enabled with it open. */
    void readWindowLoaded()
    {
        windowLoaded = client.getWidget(InterfaceID.FossilDriftnet.INV) != null;
    }

    void arm()
    {
        bankedSinceOpen = false;
        blockMessageSent = false;
        log.debug("[close] guard armed");
    }

    /** True once the catch is banked (or binned) since the window opened. */
    boolean isBankedSinceOpen()
    {
        return bankedSinceOpen;
    }

    void onClientTick()
    {
        // Only the window loading or closing arms the guard. The item grid also shows again
        // after a confirmed bank that left fish over, and that must not undo
        // the disarm. Cached gates first: the window is read only while the
        // guard can block Escape at all and the interface is loaded.
        if (!bankBeforeClose || bankedSinceOpen || !windowLoaded)
        {
            setEscapeBlocked(false);
            return;
        }
        setEscapeBlocked(isDriftNetOpen());
    }

    /** Writes the Escape block flag, logging only when it changes. */
    void setEscapeBlocked(boolean blocked)
    {
        if (escapeBlocked != blocked)
        {
            escapeBlocked = blocked;
            log.debug("[close] escape {}", blocked ? "blocked" : "allowed");
        }
    }

    /**
     * Disarms the guard on the bank or destroy confirmation click. True for
     * such a click, which every other check then leaves alone.
     */
    boolean disarmOnConfirm(MenuOptionClicked event)
    {
        // Ahead of every gate. The confirmation replaces the item grid, so
        // isDriftNetOpen() reads false while it is up. Unconditional on purpose: a
        // refused bank is not fixable from inside the window, so staying armed
        // would trap the player. Read by the fish-move block as well, so it cannot
        // sit behind the close guard's own setting either.
        if (event.getParam1() != InterfaceID.FossilDriftnet.CONFIRM_BANK
            && event.getParam1() != InterfaceID.FossilDriftnet.CONFIRM_DESTROY)
        {
            return false;
        }
        if (!bankedSinceOpen)
        {
            log.debug("[close] guard disarmed ({})",
                event.getParam1() == InterfaceID.FossilDriftnet.CONFIRM_BANK ? "bank confirmed" : "destroy confirmed");
        }
        bankedSinceOpen = true;
        return true;
    }

    /** Takes a click that would close the window on an unbanked catch. */
    void consumeIfClosingClick(MenuOptionClicked event)
    {
        if (!bankBeforeClose || bankedSinceOpen || !isDriftNetOpen())
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
        sendBlockMessage();
    }

    /** The bank-first chat line, once per arming. Client thread only. */
    void sendBlockMessage()
    {
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

    /**
     * Escape closes the drift net window without a menu click, discarding an
     * unbanked catch the same way its Close button does. Runs on the AWT
     * thread. True when a pressed Escape was taken, so the caller can queue
     * the chat line on the client thread.
     */
    boolean consumeIfBlockedEscape(KeyEvent e)
    {
        if (!escapeBlocked
            || (e.getKeyCode() != KeyEvent.VK_ESCAPE && e.getKeyChar() != KeyEvent.VK_ESCAPE))
        {
            return false;
        }

        e.consume();
        if (e.getID() != KeyEvent.KEY_PRESSED)
        {
            return false;
        }
        log.debug("[close] consumed escape");
        return true;
    }

    boolean isDriftNetOpen()
    {
        Widget w = client.getWidget(InterfaceID.FossilDriftnet.INV);
        return w != null && !w.isHidden();
    }

    /** One widget read per client cycle, for the menu build that asks once per matching entry. */
    boolean isDriftNetOpenThisCycle()
    {
        int cycle = client.getGameCycle();
        if (cycle != openReadCycle)
        {
            openReadCycle = cycle;
            openRead = isDriftNetOpen();
        }
        return openRead;
    }
}
