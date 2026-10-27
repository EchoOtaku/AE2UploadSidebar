package dev.echo.uploadsidebar.server;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternContainer;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** Uses the same active-machine boundary as AE2's own pattern access terminal. */
final class ProviderAccess {
    private ProviderAccess() { }

    static List<PatternContainer> listAvailableProvidersFromGrid(IGrid grid) {
        var result = new ArrayList<PatternContainer>();
        for (Class<?> machine : grid.getMachineClasses()) {
            if (!PatternContainer.class.isAssignableFrom(machine)) continue;
            for (var provider : grid.getActiveMachines(machine.asSubclass(PatternContainer.class))) {
                if (provider.getGrid() == grid && provider.isVisibleInTerminal()) result.add(provider);
            }
        }
        return result;
    }

    static Component getProviderDisplayNameComponent(PatternContainer provider) {
        var group = provider.getTerminalGroup();
        return group != null && group.name() != null ? group.name() : Component.translatable("block.ae2.pattern_provider");
    }

    static boolean isAccessiblePatternSlot(PatternContainer provider, int slot) {
        var inventory = provider.getTerminalPatternInventory();
        return inventory != null && slot >= 0 && slot < inventory.size() && inventory.getSlotLimit(slot) > 0;
    }

    static int getAccessiblePatternSlotCount(PatternContainer provider) {
        var inventory = provider.getTerminalPatternInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (isAccessiblePatternSlot(provider, slot)) count++;
        return count;
    }

    static int getAvailableSlots(PatternContainer provider) {
        var inventory = provider.getTerminalPatternInventory();
        if (inventory == null) return 0;
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++)
            if (isAccessiblePatternSlot(provider, slot) && inventory.getStackInSlot(slot).isEmpty()) count++;
        return count;
    }
}
