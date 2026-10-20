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
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * The tunnel and trident dialog guards: the safe line is highlighted, and a
 * click or number key on the blocked line is taken. Driven by
 * {@link DriftNetFeature}'s event handlers and key listener.
 */
@Slf4j
@Singleton
public class DialogGuard
{
    // The tunnel's already-paid dialog. Both markers are required so the guard
    // cannot fire on some other dialog that happens to offer an instance.
    private static final String ENTER_INSTANCE = "Enter instance.";
    private static final String DONT_ENTER = "Don't enter.";
    private static final String FREE_ACCESS_MARKER = "permanent free access";

    // Deep-water trident warning dialog (e.g. wielding the Merfolk trident in
    // the area): "Play it safe." cancels the wield; the other line keeps it.
    // Both lines are required, as other dialogs offer "Play it safe." too.
    private static final String PLAY_IT_SAFE = "Play it safe.";
    private static final String WIELD_ANYWAY_MARKER = "wield this anyway";
    private static final int HIGHLIGHT_GREEN = 0x0d7a0d;

    @Inject
    private Client client;

    @Inject
    private BetterDriftNetConfig config;

    // Bit n set when number key n selects a blocked dialog line. Written on
    // the client thread each client tick, read by the key listener on the
    // AWT thread, which must not touch client state itself.
    private volatile int blockedDialogDigits;

    // Config values read on the client tick and click paths. Same threading
    // as the feature's cached values.
    private volatile boolean tridentWarningGuard;
    private volatile boolean tunnelDialogGuard;

    // Set while the chat options interface is loaded, so the tunnel dialog
    // highlight only reads widgets while a dialog can be up.
    private boolean chatOptionsOpen;

    // Dialog lines turned green, with their colour before, so a guard turned
    // off (or the plugin stopping) can put them back. Client thread only.
    private final Map<Widget, Integer> tunnelHighlights = new IdentityHashMap<>();
    private final Map<Widget, Integer> tridentHighlights = new IdentityHashMap<>();

    // The dialog the guards last parsed (its lines, their text, the guards that
    // applied and the digits found), so an unchanged dialog is not parsed again
    // every client tick. Null when nothing is remembered. Client thread only.
    private Widget[] parsedLines;
    private String[] parsedTexts;
    private boolean parsedTunnel;
    private boolean parsedTrident;
    private int parsedDigits;

    void refreshConfig()
    {
        tridentWarningGuard = config.tridentWarningGuard();
        tunnelDialogGuard = config.tunnelDialogGuard();
        log.debug("[config] tridentGuard={} tunnelGuard={}", tridentWarningGuard, tunnelDialogGuard);
    }

    void startUp()
    {
        blockedDialogDigits = 0;
        // True so a dialog already open at startup is still checked; the first
        // client tick clears it when there is none.
        chatOptionsOpen = true;
        parsedLines = null;
        parsedTexts = null;
    }

    /** Stops the guards; the colours they changed are put back by {@link #restoreHighlights()}. */
    void shutDown()
    {
        blockedDialogDigits = 0;
        chatOptionsOpen = false;
    }

    /**
     * Puts back every highlighted line's colour and forgets the parsed dialog,
     * so no line widget is held. Client thread only.
     */
    void restoreHighlights()
    {
        restoreHighlights(tunnelHighlights);
        restoreHighlights(tridentHighlights);
        parsedLines = null;
        parsedTexts = null;
    }

    /**
     * True while the client tick has dialog work: a chat options interface is up
     * and a guard is on, or a guard turned off still holds highlights to put
     * back or number keys to release.
     */
    boolean needsUpdate()
    {
        return chatOptionsOpen
            && (tridentWarningGuard || tunnelDialogGuard
                || !tunnelHighlights.isEmpty() || !tridentHighlights.isEmpty()
                || blockedDialogDigits != 0);
    }

    boolean isChatOptionsOpen()
    {
        return chatOptionsOpen;
    }

    void onChatOptionsLoaded()
    {
        chatOptionsOpen = true;
    }

    void onChatOptionsClosed()
    {
        chatOptionsOpen = false;
        setBlockedDialogDigits(0);
        forgetHighlights();
    }

    /**
     * Highlights the safe line of the tunnel and trident dialogs and records
     * which number keys would pick the blocked line, for the key listener.
     */
    void updateDialogGuards(boolean inArea, boolean inFishingZone)
    {
        boolean tunnel = tunnelDialogGuard;
        boolean trident = tridentWarningGuard && inArea && inFishingZone;
        // A guard turned off, or the trident guard out of the zone, puts back
        // the colours it changed.
        if (!tunnel && !tunnelHighlights.isEmpty())
        {
            restoreHighlights(tunnelHighlights);
            parsedTexts = null;
        }
        if (!trident && !tridentHighlights.isEmpty())
        {
            restoreHighlights(tridentHighlights);
            parsedTexts = null;
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
            parsedTexts = null;
            setBlockedDialogDigits(0);
            forgetHighlights();
            return;
        }
        Widget[] lines = options.getDynamicChildren();
        if (lines == null)
        {
            parsedTexts = null;
            setBlockedDialogDigits(0);
            return;
        }
        if (isParsedDialog(lines, tunnel, trident))
        {
            setBlockedDialogDigits(parsedDigits);
            return;
        }

        // A new option list can replace the old one inside the open chat menu,
        // reusing its line widgets, so the old highlights go before new ones.
        restoreHighlights();
        // Each line's tags are stripped once here and the plain texts reused.
        String[] plain = plainTexts(lines);
        int digits = 0;
        if (tunnel && isInstanceEntryDialog(plain))
        {
            // Green "Enter instance." on the tunnel's already-paid dialog.
            highlightLines(lines, plain, DialogGuard::isEnterInstance, tunnelHighlights);
            digits |= blockedDigits(plain, firstOptionIndex(plain, DialogGuard::isTunnelOption), DONT_ENTER);
        }
        if (trident && isTridentWarningDialog(plain))
        {
            // Green "wield it anyway" line of the deep-water trident warning.
            highlightLines(lines, plain, DialogGuard::isWieldAnyway, tridentHighlights);
            digits |= blockedDigits(plain, firstOptionIndex(plain, DialogGuard::isTridentOption), PLAY_IT_SAFE);
        }
        rememberDialog(lines, tunnel, trident, digits);
        setBlockedDialogDigits(digits);
    }

    /**
     * True when the dialog is the one parsed last: same line widgets, same
     * text, same guards, and the highlighted lines still show the highlight.
     */
    private boolean isParsedDialog(Widget[] lines, boolean tunnel, boolean trident)
    {
        if (parsedTexts == null || parsedTunnel != tunnel || parsedTrident != trident
            || parsedLines.length != lines.length)
        {
            return false;
        }
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i] != parsedLines[i] || (lines[i] != null && !Objects.equals(lines[i].getText(), parsedTexts[i])))
            {
                return false;
            }
        }
        return stillHighlighted(tunnelHighlights) && stillHighlighted(tridentHighlights);
    }

    private static boolean stillHighlighted(Map<Widget, Integer> highlights)
    {
        for (Widget line : highlights.keySet())
        {
            if (line.getTextColor() != HIGHLIGHT_GREEN)
            {
                return false;
            }
        }
        return true;
    }

    private void rememberDialog(Widget[] lines, boolean tunnel, boolean trident, int digits)
    {
        parsedLines = lines.clone();
        parsedTexts = new String[lines.length];
        for (int i = 0; i < lines.length; i++)
        {
            parsedTexts[i] = lines[i] == null ? null : lines[i].getText();
        }
        parsedTunnel = tunnel;
        parsedTrident = trident;
        parsedDigits = digits;
    }

    /** Writes the blocked digit mask, logging only when it changes. */
    void setBlockedDialogDigits(int digits)
    {
        int old = blockedDialogDigits;
        blockedDialogDigits = digits;
        if (old != digits && log.isDebugEnabled())
        {
            log.debug("[dialog] blocked digits {} -> {}", Integer.toBinaryString(old), Integer.toBinaryString(digits));
        }
    }

    /** Takes a click on the tunnel dialog's "Don't enter." line. True when it was taken. */
    boolean consumeTunnelClick(MenuOptionClicked event)
    {
        if (!tunnelDialogGuard || !isDontEnterClick(event))
        {
            return false;
        }
        event.consume();
        log.debug("[dialog] blocked Don't enter click");
        if (config.showBlockMessages())
        {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                "[Safety] 'Don't enter' blocked. Pick Enter instance.", null);
        }
        return true;
    }

    /**
     * Takes a click on the trident dialog's "Play it safe." line while the
     * player stands in the hunting zone. True when it was taken.
     */
    boolean consumeTridentClick(MenuOptionClicked event, boolean inFishingZone)
    {
        if (!tridentWarningGuard || !inFishingZone || !isPlayItSafeClick(event))
        {
            return false;
        }
        event.consume();
        log.debug("[dialog] blocked Play it safe click");
        if (config.showBlockMessages())
        {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                "[Safety] 'Play it safe' blocked. Wield it and stay out of deep water.", null);
        }
        return true;
    }

    // The number keys pick a dialog line without going through
    // MenuOptionClicked. Runs on the AWT thread, so it only reads the digits
    // the client tick recorded and never touches client state.
    void consumeIfBlockedDialogDigit(KeyEvent e)
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
    private static int blockedDigits(String[] plain, int firstOptionIndex, String blockedLine)
    {
        if (firstOptionIndex < 0)
        {
            return 0;
        }

        int digits = 0;
        for (int digit = 1; digit <= 9; digit++)
        {
            int index = firstOptionIndex + digit - 1;
            if (index >= plain.length)
            {
                break;
            }
            if (blockedLine.equalsIgnoreCase(plain[index]))
            {
                digits |= 1 << digit;
            }
        }
        return digits;
    }

    /** Index of the first line whose tag-free text the predicate accepts, or -1. */
    private static int firstOptionIndex(String[] plain, Predicate<String> isOption)
    {
        for (int i = 0; i < plain.length; i++)
        {
            if (plain[i] != null && isOption.test(plain[i]))
            {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTridentOption(String line)
    {
        return PLAY_IT_SAFE.equalsIgnoreCase(line) || isWieldAnyway(line);
    }

    private static boolean isTunnelOption(String line)
    {
        return ENTER_INSTANCE.equalsIgnoreCase(line) || DONT_ENTER.equalsIgnoreCase(line);
    }

    private static boolean isWieldAnyway(String line)
    {
        return line.toLowerCase(Locale.ROOT).contains(WIELD_ANYWAY_MARKER);
    }

    private static boolean isEnterInstance(String line)
    {
        return ENTER_INSTANCE.equalsIgnoreCase(line);
    }

    /** Each line's tag-free text, null where the line or its text is missing. */
    private static String[] plainTexts(Widget[] lines)
    {
        String[] plain = new String[lines.length];
        for (int i = 0; i < lines.length; i++)
        {
            String text = lines[i] == null ? null : lines[i].getText();
            plain[i] = text == null ? null : Text.removeTags(text);
        }
        return plain;
    }

    /**
     * Highlights every line whose tag-free text the predicate accepts. The
     * colour before is kept in the given map.
     */
    private static void highlightLines(Widget[] lines, String[] plain, Predicate<String> matches,
        Map<Widget, Integer> highlights)
    {
        for (int i = 0; i < lines.length; i++)
        {
            if (plain[i] != null && matches.test(plain[i]))
            {
                highlight(lines[i], plain[i], highlights);
            }
        }
    }

    private static void highlight(Widget line, String plain, Map<Widget, Integer> highlights)
    {
        if (!highlights.containsKey(line))
        {
            highlights.put(line, line.getTextColor());
            log.debug("[dialog] highlight applied: {}", plain);
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
        parsedLines = null;
        parsedTexts = null;
        tunnelHighlights.clear();
        tridentHighlights.clear();
    }

    /** True when the click landed on the tunnel dialog's "Don't enter." line. */
    private boolean isDontEnterClick(MenuOptionClicked event)
    {
        return clickedLineMatches(event, DialogGuard::isInstanceEntryDialog, DONT_ENTER);
    }

    /**
     * True when the click landed on the blocked line of a dialog the
     * predicate identifies from its tag-free lines.
     */
    private boolean clickedLineMatches(MenuOptionClicked event, Predicate<String[]> isDialog, String blockedLine)
    {
        Widget[] lines = clickedDialogLines(event);
        int index = event.getParam0();
        if (clickedLine(lines, index) == null)
        {
            return false;
        }
        String[] plain = plainTexts(lines);
        return isDialog.test(plain) && blockedLine.equalsIgnoreCase(plain[index]);
    }

    /** Both markers together identify the tunnel's already-paid dialog. */
    private static boolean isInstanceEntryDialog(String[] plain)
    {
        boolean enter = false;
        boolean freeAccess = false;
        for (String line : plain)
        {
            if (line == null)
            {
                continue;
            }
            enter |= isEnterInstance(line);
            freeAccess |= line.toLowerCase(Locale.ROOT).contains(FREE_ACCESS_MARKER);
        }
        return enter && freeAccess;
    }

    /** The dialog's option lines when the click was on one of them, else null. */
    private Widget[] clickedDialogLines(MenuOptionClicked event)
    {
        if (event.getParam1() != InterfaceID.Chatmenu.OPTIONS)
        {
            return null;
        }
        Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        return options == null ? null : options.getDynamicChildren();
    }

    /** The clicked line, or null when the index is outside the lines. */
    private static Widget clickedLine(Widget[] lines, int index)
    {
        return lines == null || index < 0 || index >= lines.length ? null : lines[index];
    }

    /** Both lines together identify the deep-water trident warning. */
    private static boolean isTridentWarningDialog(String[] plain)
    {
        boolean playItSafe = false;
        boolean wieldAnyway = false;
        for (String line : plain)
        {
            if (line == null)
            {
                continue;
            }
            playItSafe |= PLAY_IT_SAFE.equalsIgnoreCase(line);
            wieldAnyway |= isWieldAnyway(line);
        }
        return playItSafe && wieldAnyway;
    }

    /** True when the click landed on the trident warning's "Play it safe." line. */
    private boolean isPlayItSafeClick(MenuOptionClicked event)
    {
        return clickedLineMatches(event, DialogGuard::isTridentWarningDialog, PLAY_IT_SAFE);
    }
}
