package dev.jwalkin.create_commerce.data;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Village commerce state sent server→client for Trade Terminal rendering.
 */
public record VillageSnapshot(
        UUID villageId,
        String customName,
        String profileDisplayName,
        Map<String, Integer> capConsumed,
        Map<String, Integer> capMax,
        int structureCount,
        List<String> acceptedItems,
        List<Integer> acceptedInputAmounts,
        List<Integer> acceptedPayouts,
        List<Integer> acceptedConsumed,
        List<Integer> acceptedCapMax,
        List<String> bonusItems,
        List<String> refusedItems,
        List<String> terminalNames,
        int villagerCount
) {
    public static final StreamCodec<FriendlyByteBuf, VillageSnapshot> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public VillageSnapshot decode(FriendlyByteBuf buf) {
                    UUID id = buf.readUUID();
                    String custom = buf.readUtf(64);
                    String profile = buf.readUtf();

                    int capCount = buf.readVarInt();
                    Map<String, Integer> consumed = new HashMap<>();
                    Map<String, Integer> max = new HashMap<>();
                    for (int i = 0; i < capCount; i++) {
                        String cat = buf.readUtf();
                        int cons = buf.readVarInt();
                        int mx = buf.readVarInt();
                        consumed.put(cat, cons);
                        max.put(cat, mx);
                    }
                    int structures = buf.readVarInt();

                    int acceptCount = buf.readVarInt();
                    List<String> accepts = new ArrayList<>(acceptCount);
                    List<Integer> inputs = new ArrayList<>(acceptCount);
                    List<Integer> payouts = new ArrayList<>(acceptCount);
                    List<Integer> consumedPerItem = new ArrayList<>(acceptCount);
                    List<Integer> capMaxPerItem = new ArrayList<>(acceptCount);
                    for (int i = 0; i < acceptCount; i++) {
                        accepts.add(buf.readUtf(128));
                        inputs.add(buf.readVarInt());
                        payouts.add(buf.readVarInt());
                        consumedPerItem.add(buf.readVarInt());
                        capMaxPerItem.add(buf.readVarInt());
                    }

                    int bonusCount = buf.readVarInt();
                    List<String> bonus = new ArrayList<>(bonusCount);
                    for (int i = 0; i < bonusCount; i++) bonus.add(buf.readUtf(128));

                    int refuseCount = buf.readVarInt();
                    List<String> refuses = new ArrayList<>(refuseCount);
                    for (int i = 0; i < refuseCount; i++) refuses.add(buf.readUtf(128));

                    int nameCount = buf.readVarInt();
                    List<String> names = new ArrayList<>(nameCount);
                    for (int i = 0; i < nameCount; i++) names.add(buf.readUtf(64));

                    int villagers = buf.readVarInt();

                    return new VillageSnapshot(id, custom, profile, consumed, max, structures,
                            accepts, inputs, payouts, consumedPerItem, capMaxPerItem,
                            bonus, refuses, names, villagers);
                }

                @Override
                public void encode(FriendlyByteBuf buf, VillageSnapshot snap) {
                    buf.writeUUID(snap.villageId());
                    buf.writeUtf(snap.customName() == null ? "" : snap.customName(), 64);
                    buf.writeUtf(snap.profileDisplayName());

                    buf.writeVarInt(snap.capConsumed().size());
                    for (var entry : snap.capConsumed().entrySet()) {
                        buf.writeUtf(entry.getKey());
                        buf.writeVarInt(entry.getValue());
                        buf.writeVarInt(snap.capMax().getOrDefault(entry.getKey(), 512));
                    }
                    buf.writeVarInt(snap.structureCount());

                    buf.writeVarInt(snap.acceptedItems().size());
                    for (int i = 0; i < snap.acceptedItems().size(); i++) {
                        buf.writeUtf(snap.acceptedItems().get(i), 128);
                        buf.writeVarInt(snap.acceptedInputAmounts().get(i));
                        buf.writeVarInt(snap.acceptedPayouts().get(i));
                        // Per-item cap state.
                        buf.writeVarInt(i < snap.acceptedConsumed().size() ? snap.acceptedConsumed().get(i) : 0);
                        buf.writeVarInt(i < snap.acceptedCapMax().size() ? snap.acceptedCapMax().get(i) : 0);
                    }

                    buf.writeVarInt(snap.bonusItems().size());
                    for (String b : snap.bonusItems()) buf.writeUtf(b, 128);

                    buf.writeVarInt(snap.refusedItems().size());
                    for (String r : snap.refusedItems()) buf.writeUtf(r, 128);

                    buf.writeVarInt(snap.terminalNames().size());
                    for (String n : snap.terminalNames()) buf.writeUtf(n, 64);

                    buf.writeVarInt(snap.villagerCount());
                }
            };
}
