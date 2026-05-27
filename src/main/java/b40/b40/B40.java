package b40.b40;

import b40.b40.item.AdminWandItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemAttributeModifiers;

public class B40 implements ModInitializer {
    private static final AntiCheatManager ANTI_CHEAT = new AntiCheatManager();
    public static final String MOD_ID = "b40";

    private static Item registerItem(String name, java.util.function.Function<Item.Properties, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, name));
        Item.Properties properties = new Item.Properties().setId(key);
        Item item = factory.apply(properties);
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    public static final Item ADMIN_ITEM = registerItem(
            "admin",
            properties -> new AdminWandItem(properties
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
                    .attributes(ItemAttributeModifiers.builder()
                            .add(
                                    Attributes.ATTACK_DAMAGE,
                                    new AttributeModifier(
                                            Identifier.fromNamespaceAndPath(MOD_ID, "admin_damage"),
                                            3667.0,
                                            AttributeModifier.Operation.ADD_VALUE),
                                    EquipmentSlotGroup.MAINHAND)
                            .build())));

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> entries.accept(ADMIN_ITEM));
        ANTI_CHEAT.init();
    }
}
