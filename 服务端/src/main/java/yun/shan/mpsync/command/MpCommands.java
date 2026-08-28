package yun.shan.mpsync.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.Member;
import yun.shan.mpsync.room.QueueEntry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;

/**
 * {@code /mp ...} — the chat-side control surface.
 *
 * <p>Every mutating subcommand builds the same JSON frame the client GUI would send and
 * feeds it through {@link RoomManager#handle}, so permissions, validation, and broadcasts
 * live in exactly one place. Only the read-only views ({@code info}, {@code queue},
 * {@code list}) touch room state directly.
 */
public final class MpCommands {

    private MpCommands() {}

    public static void register(RoomManager manager) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("mp");

            root.then(CommandManager.literal("create")
                    .executes(ctx -> op(manager, ctx, Proto.C_CREATE, o -> {}))
                    .then(CommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(ctx -> op(manager, ctx, Proto.C_CREATE, o ->
                                    o.addProperty("name", StringArgumentType.getString(ctx, "name"))))));

            root.then(CommandManager.literal("join")
                    .then(CommandManager.argument("code", StringArgumentType.word())
                            .executes(ctx -> op(manager, ctx, Proto.C_JOIN, o ->
                                    o.addProperty("code", StringArgumentType.getString(ctx, "code"))))));

            root.then(CommandManager.literal("leave")
                    .executes(ctx -> op(manager, ctx, Proto.C_LEAVE, o -> {})));
            root.then(CommandManager.literal("disband")
                    .executes(ctx -> op(manager, ctx, Proto.C_DISBAND, o -> {})));

            root.then(playerArg(manager, "invite", Proto.C_INVITE, null));
            root.then(playerArg(manager, "admin", Proto.C_SET_ROLE, "admin"));
            root.then(playerArg(manager, "unadmin", Proto.C_SET_ROLE, "member"));
            root.then(playerArg(manager, "kick", Proto.C_KICK, null));
            root.then(playerArg(manager, "transfer", Proto.C_TRANSFER, null));

            root.then(CommandManager.literal("pause")
                    .executes(ctx -> op(manager, ctx, Proto.C_PAUSE, o -> {})));
            root.then(CommandManager.literal("resume")
                    .executes(ctx -> op(manager, ctx, Proto.C_RESUME, o -> {})));
            root.then(CommandManager.literal("skip")
                    .executes(ctx -> op(manager, ctx, Proto.C_NEXT, o -> {})));
            root.then(CommandManager.literal("prev")
                    .executes(ctx -> op(manager, ctx, Proto.C_PREV, o -> {})));
            root.then(CommandManager.literal("clear")
                    .executes(ctx -> op(manager, ctx, Proto.C_CLEAR, o -> {})));

            root.then(CommandManager.literal("seek")
                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0))
                            .executes(ctx -> op(manager, ctx, Proto.C_SEEK, o ->
                                    o.addProperty("pos",
                                            IntegerArgumentType.getInteger(ctx, "seconds") * 1000L)))));

            root.then(CommandManager.literal("repeat")
                    .then(CommandManager.argument("mode", StringArgumentType.word())
                            .executes(ctx -> op(manager, ctx, Proto.C_REPEAT, o ->
                                    o.addProperty("mode", StringArgumentType.getString(ctx, "mode"))))));

            root.then(CommandManager.literal("play")
                    .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                            .executes(ctx -> op(manager, ctx, Proto.C_PLAY_INDEX, o ->
                                    o.addProperty("index",
                                            IntegerArgumentType.getInteger(ctx, "index") - 1)))));

            root.then(CommandManager.literal("info").executes(ctx -> info(manager, ctx)));
            root.then(CommandManager.literal("queue").executes(ctx -> queue(manager, ctx)));
            root.then(CommandManager.literal("list")
                    .requires(src -> src.hasPermissionLevel(2))
                    .executes(ctx -> list(manager, ctx)));

            root.executes(ctx -> help(ctx));
            dispatcher.register(root);
        });
    }

    private interface Filler { void fill(JsonObject o); }

    private static LiteralArgumentBuilder<ServerCommandSource> playerArg(
            RoomManager manager, String literal, String protoOp, String role) {
        return CommandManager.literal(literal)
                .then(CommandManager.argument("target", EntityArgumentType.player())
                        .executes(ctx -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
                            return op(manager, ctx, protoOp, o -> {
                                o.addProperty("target", target.getGameProfile().getName());
                                if (role != null) o.addProperty("role", role);
                            });
                        }));
    }

    private static int op(RoomManager manager, CommandContext<ServerCommandSource> ctx,
                          String protoOp, Filler filler) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        JsonObject msg = Proto.msg(protoOp);
        filler.fill(msg);
        manager.handle(player, msg);
        return 1;
    }

    private static int info(RoomManager manager, CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        Room room = manager.roomOf(player);
        if (room == null) {
            feedback(ctx, "你还没有加入一起听房间，使用 /mp create 创建或 /mp join <邀请码> 加入");
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("房间：").append(room.name).append("  邀请码：").append(room.code)
                .append("  循环：").append(room.repeat().id()).append('\n');
        QueueEntry cur = room.current();
        sb.append("正在播放：")
                .append(cur == null ? "（无）" : cur.track.display() + "  " + fmt(room.positionMs())
                        + "/" + fmt(cur.track.durationMs) + (room.isPlaying() ? " ▶" : " ⏸"))
                .append('\n');
        sb.append("成员（").append(room.members().size()).append("，收听中 ")
                .append(room.listenerCount()).append("）：");
        boolean first = true;
        for (Member m : room.members()) {
            if (!first) sb.append("，");
            first = false;
            sb.append(m.name).append('[').append(m.role.id()).append(m.online ? "" : ",离线").append(']');
        }
        feedback(ctx, sb.toString());
        return 1;
    }

    private static int queue(RoomManager manager, CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        Room room = manager.roomOf(player);
        if (room == null) { feedback(ctx, "你还没有加入一起听房间"); return 0; }
        if (room.queue().isEmpty()) { feedback(ctx, "一起听歌单为空"); return 1; }
        StringBuilder sb = new StringBuilder("一起听歌单（").append(room.queue().size()).append("）：");
        int i = 1;
        for (QueueEntry e : room.queue()) {
            sb.append('\n').append(i++).append(". ").append(e.track.display())
                    .append("  — ").append(e.requesterName);
        }
        feedback(ctx, sb.toString());
        return 1;
    }

    private static int list(RoomManager manager, CommandContext<ServerCommandSource> ctx) {
        if (manager.rooms().isEmpty()) { feedback(ctx, "当前没有一起听房间"); return 1; }
        StringBuilder sb = new StringBuilder("一起听房间：");
        for (Room r : manager.rooms()) {
            sb.append('\n').append(r.code).append("  ").append(r.name)
                    .append("  成员 ").append(r.members().size())
                    .append("  队列 ").append(r.queue().size())
                    .append("  ").append(r.current() == null ? "空闲" : r.current().track.display());
        }
        feedback(ctx, sb.toString());
        return 1;
    }

    private static int help(CommandContext<ServerCommandSource> ctx) {
        feedback(ctx, String.join("\n",
                "一起听指令：",
                "/mp create [房名]         创建房间",
                "/mp join <邀请码>         加入房间",
                "/mp invite <玩家>         邀请玩家",
                "/mp leave | /mp disband   离开 | 解散（房主）",
                "/mp admin <玩家> | /mp unadmin <玩家>   授予/撤销管理员",
                "/mp transfer <玩家> | /mp kick <玩家>   转让房主 | 移出成员",
                "/mp pause | resume | skip | prev | clear",
                "/mp play <序号> | /mp seek <秒> | /mp repeat <off|one|all>",
                "/mp info | /mp queue      房间信息 | 一起听歌单",
                "点歌与切歌请在音乐播放器界面操作（需安装 Melodify 客户端 mod）"));
        return 1;
    }

    private static void feedback(CommandContext<ServerCommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(() -> Text.literal(text), false);
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
