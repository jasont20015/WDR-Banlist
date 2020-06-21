package com.wdrbanlist;

import net.runelite.client.input.KeyListener;

import javax.inject.Inject;
import java.awt.event.KeyEvent;

public class WdrBanlistInputListener implements KeyListener {
    private static final int HOTKEY = KeyEvent.VK_SHIFT;

    private final WdrBanlistPlugin plugin;

    @Inject
    private WdrBanlistInputListener(WdrBanlistPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void keyTyped(KeyEvent e) {

    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == HOTKEY) {
            plugin.setHotKeyPressed(true);
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == HOTKEY) {
            plugin.setHotKeyPressed(false);
        }
    }
}
