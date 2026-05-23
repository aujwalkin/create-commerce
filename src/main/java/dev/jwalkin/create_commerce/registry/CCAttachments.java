package dev.jwalkin.create_commerce.registry;

import dev.jwalkin.create_commerce.CreateCommerce;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Reserved for future per-player data. */
public class CCAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CreateCommerce.MOD_ID);
}
