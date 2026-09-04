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
package com.vividflash.betterdriftnet;

import com.google.inject.Provides;
import com.vividflash.betterdriftnet.features.DriftNetFeature;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
    name = "Better Drift Net",
    description = "Improved Drift net fishing and misclick guards",
    tags = {"drift", "net", "driftnet", "fossil", "island", "underwater", "fishing", "hunter", "guard", "misclick"}
)
public class BetterDriftNetPlugin extends Plugin
{
    private static final String CONFIG_GROUP = "betterdriftnet";
    private static final String ALLOW_LIST_KEY = "stillMovableItems";

    /**
     * Records which release last migrated the profile. Version-stamped rather
     * than a flag, so a later release can add a step and run again.
     */
    private static final String MIGRATION_KEY = "migratedVersion";
    private static final String MIGRATION_VERSION = "1.2";

    /**
     * The exception list 1.0 and 1.1 shipped. It named a clue box, which drift
     * net fishing never produces.
     */
    private static final String STALE_ALLOW_LIST = "Pufferfish, Numulite, *fossil*, Clue box*";

    /**
     * Keys from settings 1.0 shipped and a later release dropped, cleared from
     * profiles by the sweep. Add to this list and bump
     * {@link #MIGRATION_VERSION} together.
     */
    private static final String[] DEAD_KEYS = {"highlightFullNets", "highlightAnnette"};

    @Inject
    private DriftNetFeature driftNetFeature;

    @Inject
    private BetterDriftNetConfig config;

    @Inject
    private ConfigManager configManager;

    @Override
    protected void startUp()
    {
        migrateOnce();
        driftNetFeature.startUp();
    }

    @Override
    protected void shutDown()
    {
        driftNetFeature.shutDown();
    }

    /**
     * One-time repair of settings an earlier release left wrong, gated so it
     * never overrides a choice the user has since made.
     */
    private void migrateOnce()
    {
        if (MIGRATION_VERSION.equals(configManager.getConfiguration(CONFIG_GROUP, MIGRATION_KEY)))
        {
            return;
        }
        configManager.setConfiguration(CONFIG_GROUP, MIGRATION_KEY, MIGRATION_VERSION);

        // A profile still on the old list gets today's; an edited one is left
        // alone. Unset first so the proxy reads the default rather than the
        // stored value, then write it back so the panel shows it.
        if (STALE_ALLOW_LIST.equals(config.stillMovableItems()))
        {
            configManager.unsetConfiguration(CONFIG_GROUP, ALLOW_LIST_KEY);
            configManager.setConfiguration(CONFIG_GROUP, ALLOW_LIST_KEY, config.stillMovableItems());
        }

        // Last, so a step above can still read what it retires.
        for (String dead : DEAD_KEYS)
        {
            configManager.unsetConfiguration(CONFIG_GROUP, dead);
        }
    }

    @Provides
    BetterDriftNetConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BetterDriftNetConfig.class);
    }
}
