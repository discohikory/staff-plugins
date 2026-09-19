package com.discohikorybrs.discologin;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Se dispara cuando un jugador completa el /login (o entra directo).
 * AuthStaff lo escucha para iniciar el 2FA después del logeo.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class DiscoLoginSuccessEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;

    public DiscoLoginSuccessEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
