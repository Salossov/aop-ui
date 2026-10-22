package aop.ui.network;

import aop.ui.config.*;
import com.google.gson.*;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class SettingsNetwork {
    public record Request(String json) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.parse("aop_ui:request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of((b,p) -> b.writeUtf(p.json, 30000), b -> new Request(b.readUtf(30000)));
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Response(String json) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(ResourceLocation.parse("aop_ui:response"));
        public static final StreamCodec<FriendlyByteBuf, Response> CODEC = StreamCodec.of((b,p) -> b.writeUtf(p.json, 262144), b -> new Response(b.readUtf(262144)));
        @Override public Type<Response> type() { return TYPE; }
    }
    private static Consumer<JsonObject> clientReceiver = p -> { };
    public static void installClient(Consumer<JsonObject> receiver) { clientReceiver = receiver; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(Request.TYPE, Request.CODEC, (p,c) -> c.enqueueWork(() -> { if (c.player() instanceof ServerPlayer player) handle(player, p.json); }));
        r.playToClient(Response.TYPE, Response.CODEC, (p,c) -> c.enqueueWork(() -> {
            try { clientReceiver.accept(JsonParser.parseString(p.json).getAsJsonObject()); }
            catch (RuntimeException e) { com.mojang.logging.LogUtils.getLogger().warn("Invalid AOP UI response", e); }
        }));
    }
    public static boolean canEdit(ServerPlayer p) {
        return canEdit(p.hasPermissions(4), p.getServer().isSingleplayer(), p.getServer().isSingleplayerOwner(p.getGameProfile()));
    }
    public static boolean canEdit(boolean operator4, boolean singleplayer, boolean owner) { return operator4 || (singleplayer && owner); }
    private static void handle(ServerPlayer player, String json) {
        var reply = new JsonObject();
        try {
            if (json.length() > 30000) throw new IllegalArgumentException("aopui.error.invalid");
            var request = JsonParser.parseString(json).getAsJsonObject();
            reply.addProperty("request", request.get("request").getAsLong());
            String id = request.get("config").getAsString();
            reply.addProperty("config", id);
            var d = NeoForgeConfigs.document(id).orElseThrow(() -> new IllegalArgumentException("aopui.error.unavailable"));
            if (d.scope() != net.neoforged.fml.config.ModConfig.Type.SERVER && d.scope() != net.neoforged.fml.config.ModConfig.Type.STARTUP)
                throw new IllegalArgumentException("aopui.error.invalid");
            boolean edit = canEdit(player);
            boolean save = request.get("operation").getAsString().equals("save");
            if (save) {
                if (!edit) throw new IllegalArgumentException("aopui.error.denied");
                d.apply(request.get("revision").getAsString(), request.getAsJsonObject("changes"));
                if (d.scope() == net.neoforged.fml.config.ModConfig.Type.SERVER) {
                    var sync = new JsonObject(); sync.addProperty("sync", id); sync.addProperty("toml", NeoForgeConfigs.serializeServer(d));
                    PacketDistributor.sendToAllPlayers(new Response(sync.toString()));
                }
            } else if (!request.get("operation").getAsString().equals("read")) throw new IllegalArgumentException("aopui.error.invalid");
            reply.addProperty("editable", edit); reply.add("snapshot", d.snapshot());
            reply.addProperty("ok", true);
        } catch (RuntimeException e) {
            reply.addProperty("ok", false);
            String message = e.getMessage();
            reply.addProperty("error", message != null && message.startsWith("aopui.error.") ? message : "aopui.error.invalid");
        }
        PacketDistributor.sendToPlayer(player, new Response(reply.toString()));
    }
    private SettingsNetwork() { }
}
