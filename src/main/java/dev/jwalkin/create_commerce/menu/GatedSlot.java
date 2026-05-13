package dev.jwalkin.create_commerce.menu;

import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * SlotItemHandler with a client-toggleable active flag. Vanilla skips
 * rendering and hover detection for inactive slots, so flipping this hides
 * deposit slots on non-Deposit tabs without masking or EditBox conflicts.
 */
public class GatedSlot extends SlotItemHandler {

    public boolean active = true;

    public GatedSlot(IItemHandler handler, int index, int x, int y) {
        super(handler, index, x, y);
    }

    @Override
    public boolean isActive() {
        return active;
    }
}
