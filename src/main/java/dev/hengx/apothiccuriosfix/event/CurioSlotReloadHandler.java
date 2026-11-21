package dev.hengx.apothiccuriosfix.event;

import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Re-registers the {@code curios:<slot>} loot categories around every data pack reload.
 *
 * <p>The slot registry of Curios is data-pack driven ({@code data/curios/slots/*.json}), so during mod
 * construction the Curios API usually reports nothing yet. This hook does two things:</p>
 * <ol>
 *     <li>one pass <em>immediately</em>, while the reload is still collecting listeners - by now the same pack
 *         manager is in place and any slot that a mod registered through the API is visible;</li>
 *     <li>a listener of its own that runs once all listeners ran, which is the first point where Curios' slot
 *         loader has definitely finished - it picks up every slot id of the packs for the next pass and for the
 *         runtime lookups (loot generation, curios GUI, ...).</li>
 * </ol>
 *
 * <p>Neither pass can be relied on to happen <em>before</em> Apotheosis parses its affix JSON - that order is
 * mod-load order - which is why {@code mixin.LootCategoryMixin} additionally creates any still unknown
 * {@code curios:*} name from inside {@code LootCategory.byId} itself. Both passes are idempotent.</p>
 */
public final class CurioSlotReloadHandler {

    private CurioSlotReloadHandler() {
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        try {
            CurioLootCategories.registerAll();
        } catch (RuntimeException | LinkageError ex) {
            // Never abort a reload: the categories this mod failed to add are reported by Apotheosis itself.
        }
        try {
            event.addListener(new SimplePreparableReloadListener<Void>() {
                @Override
                protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                    return null;
                }

                @Override
                protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                    try {
                        CurioLootCategories.registerAll();
                    } catch (RuntimeException | LinkageError ex) {
                        // See above.
                    }
                }
            });
        } catch (RuntimeException | LinkageError ex) {
            // Older/newer Forge without PreparableReloadListener support: registration above still happened.
        }
    }
}
