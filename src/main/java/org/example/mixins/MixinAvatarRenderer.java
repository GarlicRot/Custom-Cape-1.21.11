package org.example.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerSkin;
import org.example.CustomCapesPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps cape and elytra texture of the local player. High priority so it runs after other cape mixins.
 */
@Mixin(value = AvatarRenderer.class, priority = 2000)
public class MixinAvatarRenderer {

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void onExtractRenderState(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if(CustomCapesPlugin.INSTANCE == null || state.skin == null) {
			return;
		}

		final boolean isLocalPlayer = entity == Minecraft.getInstance().player;
		final ClientAsset.ResourceTexture cape = isLocalPlayer
				? CustomCapesPlugin.INSTANCE.getCapeManager().getActiveCape()
				: CustomCapesPlugin.INSTANCE.getCapeManager().getSharedCape(entity.getUUID());
		if(cape == null) {
			return;
		}

		final PlayerSkin skin = state.skin;
		state.skin = new PlayerSkin(skin.body(), cape, cape, skin.model(), skin.secure());
	}
}
