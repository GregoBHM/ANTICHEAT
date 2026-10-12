package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.item.ItemBehaviour;
import ac.grim.grimac.utils.item.ItemBehaviourRegistry;
import ac.grim.grimac.utils.item.ItemUseResetPolicy;
import ac.grim.grimac.utils.item.ItemUseSessionTracker;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import org.jetbrains.annotations.NotNull;

public class PacketPlayerDigging extends PacketListenerAbstract {

    public PacketPlayerDigging() {
        super(PacketListenerPriority.LOW);
    }

    private static final boolean SERVER_HAS_OFFHAND = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9);

    public static void handleUseItem(@NotNull GrimPlayer player, @NotNull InteractionHand hand) {
        ItemStack item = player.inventory.getItemInHand(hand);

        if (item == null) {
            player.packetStateData.setSlowedByUsingItem(false);
            player.itemUseSessionTracker.end(ItemUseResetPolicy.Reason.REAL_ITEM_CHANGE);
            return;
        }

        if (player.checkManager.getCompensatedCooldown().hasItem(item)) {
            boolean valid = !player.packetStateData.isSlowedByUsingItem()
                    || player.packetStateData.itemInUseHand == hand;
            if (valid) {
                player.packetStateData.setSlowedByUsingItem(false);
                player.itemUseSessionTracker.end(ItemUseResetPolicy.Reason.COOLDOWN);
            }
            return;
        }

        final ItemType material = item.getType();
        final ItemBehaviour itemBehaviour = ItemBehaviourRegistry.getItemBehaviour(player, material);

        if (itemBehaviour.canUse(item, player.compensatedWorld, player, hand)) {
            player.packetStateData.slowedByUsingItemTransaction =
                    player.lastTransactionReceived.get();
            player.packetStateData.setSlowedByUsingItem(true);
            player.packetStateData.itemInUseHand = hand;
            player.itemUseSessionTracker.begin(
                    hand,
                    item,
                    System.currentTimeMillis(),
                    player.lastTransactionReceived.get()
            );
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging dig = new WrapperPlayClientPlayerDigging(event);

            if (dig.getAction() == DiggingAction.RELEASE_USE_ITEM) {
                final GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
                if (player == null) return;

                boolean wasUsingItem = player.packetStateData.isSlowedByUsingItem();
                player.packetStateData.setSlowedByUsingItem(false);
                player.packetStateData.slowedByUsingItemTransaction =
                        player.lastTransactionReceived.get();
                player.itemUseSessionTracker.end(ItemUseResetPolicy.Reason.CLIENT_RELEASE);

                if (wasUsingItem && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
                    ItemStack hand = player.inventory.getItemInHand(player.packetStateData.itemInUseHand);

                    if (hand.getType() == ItemTypes.TRIDENT && hand.getEnchantmentLevel(EnchantmentTypes.RIPTIDE) > 0) {
                        player.packetStateData.tryingToRiptide = true;
                    }
                }
            }
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType()) || event.getPacketType() == PacketType.Play.Client.CLIENT_TICK_END) {
            final GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null || !player.packetStateData.isSlowedByUsingItem()) return;

            if (!player.packetStateData.lastPacketWasTeleport && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
                boolean slotChanged = player.packetStateData.itemInUseHand != InteractionHand.OFF_HAND
                        && player.packetStateData.getSlowedByUsingItemSlot() != player.packetStateData.lastSlotSelected;

                if (slotChanged) {
                    player.packetStateData.setSlowedByUsingItem(false);
                    player.itemUseSessionTracker.end(
                            ItemUseResetPolicy.Reason.REAL_SLOT_CHANGE
                    );
                    player.checkManager.getNoSlow().didSlotChangeLastTick = true;
                }
            }
        }

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            final int slot = new WrapperPlayClientHeldItemChange(event).getSlot();

            if (slot > 8 || slot < 0) return;

            final GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            CheckManagerListener.handleQueuedPlaces(player, false, 0, 0, System.currentTimeMillis());

            if (player.packetStateData.lastSlotSelected != slot) {
                if (player.isResetItemUsageOnSlotChange()) {
                    player.itemUseSessionTracker.requestServerReset(
                            ItemUseResetPolicy.Reason.REAL_SLOT_CHANGE,
                            InteractionHand.MAIN_HAND,
                            null
                    );
                } else {
                    player.itemUseSessionTracker.end(
                            ItemUseResetPolicy.Reason.REAL_SLOT_CHANGE
                    );
                }

                boolean usingInMainHand =
                        player.packetStateData.isSlowedByUsingItem()
                                && player.packetStateData.itemInUseHand
                                == InteractionHand.MAIN_HAND;
                if (usingInMainHand
                        && player.canSkipTicks()
                        && !player.isTickingReliablyFor(3)) {
                    player.packetStateData.setSlowedByUsingItem(false);
                    player.checkManager.getNoSlow().didSlotChangeLastTick = true;
                }
            }
            player.packetStateData.lastSlotSelected = slot;
        }

        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM || (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT && new WrapperPlayClientPlayerBlockPlacement(event).getFace() == BlockFace.OTHER)) {
            final GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_8)
                    && player.gamemode == GameMode.SPECTATOR)
                return;

            final InteractionHand hand = SERVER_HAS_OFFHAND && event.getPacketType() == PacketType.Play.Client.USE_ITEM
                    ? new WrapperPlayClientUseItem(event).getHand()
                    : InteractionHand.MAIN_HAND;

            InteractionHand activeHand = GrimAPI.INSTANCE
                    .getItemResetHandler()
                    .getItemUsageHand(player.platformPlayer);
            boolean preserveLegacyUse = ItemUseResetPolicy.preserveRepeatedUse(
                    player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8),
                    player.packetStateData.isSlowedByUsingItem(),
                    activeHand == hand
            );

            if (player.isResetItemUsageOnItemUse() && !preserveLegacyUse) {
                player.itemUseSessionTracker.requestServerReset(
                        ItemUseResetPolicy.Reason.REPEATED_USE,
                        hand,
                        player.inventory.getItemInHand(hand)
                );
            }

            handleUseItem(player, hand);
        }
    }
}
