package yun.shan.mpsync.command;

import com.google.gson.JsonObject;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.Member;
import yun.shan.mpsync.room.QueueEntry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /mp ...} implemented once, as plain string arguments.
 *
 * <p>Brigadier (Fabric) and Bukkit commands (Paper) differ in every detail except the
 * words the player types, so both adapters register a thin wrapper that forwards the raw
 * argument array here. Every mutating subcommand builds the same JSON frame the client
 * GUI would send and pushes it through {@link RoomManager#handle}, so permissions,
 * validation and broadcasts exist in exactly one place; only the read-only views
 * ({@code info}, {@code queue}, {@code list}) read room state directly.
 *
 * @return feedback lines for the caller; room-wide effects are announced by the engine
 */
public final class MpTextCommands {

    private MpTextCommands() {}

    /** Subcommands offered for tab completion (excluding the op-only {@code list}). */
    public static final List<String> SUBCOMMANDS = List.of(
            "create", "join", "leave", "disband", "invite", "admin", "unadmin", "kick",
            "transfer", "pause", "resume", "skip", "prev", "clear", "seek", "repeat",
            "play", "info", "queue", "help");

    /** Completion candidates for {@code /mp <args...>}. */
    public static List<String> suggest(String[] args, List<String> onlineNames) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : SUBCOMMANDS) if (s.startsWith(prefix)) out.add(s);
            return out;
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "invite": case "admin": case "unadmin": case "kick": case "transfer": {
                    List<String> out = new ArrayList<>();
                    String prefix = args[1].toLowerCase(Locale.ROOT);
                    if (onlineNames != null) {
                        for (String n : onlineNames) {
                            if (n.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(n);
                        }
                    }
                    return out;
                }
                case "repeat": return List.of("off", "one", "all");
                default: return List.of();
            }
        }
        return List.of();
    }

    public static List<String> run(UUID actor, boolean operator, String[] args) {
        RoomManager manager = MpParty.manager();
        if (manager == null) return List.of("一起听服务端未就绪");
        if (args.length == 0) return help();

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create": {
                JsonObject m = Proto.msg(Proto.C_CREATE);
                if (args.length > 1) m.addProperty("name", join(args, 1));
                return op(manager, actor, m);
            }
            case "join": {
                if (args.length < 2) return List.of("用法：/mp join <邀请码>");
                JsonObject m = Proto.msg(Proto.C_JOIN);
                m.addProperty("code", args[1]);
                return op(manager, actor, m);
            }
            case "leave":   return op(manager, actor, Proto.msg(Proto.C_LEAVE));
            case "disband": return op(manager, actor, Proto.msg(Proto.C_DISBAND));
            case "pause":   return op(manager, actor, Proto.msg(Proto.C_PAUSE));
            case "resume":  return op(manager, actor, Proto.msg(Proto.C_RESUME));
            case "skip":    return op(manager, actor, Proto.msg(Proto.C_NEXT));
            case "prev":    return op(manager, actor, Proto.msg(Proto.C_PREV));
            case "clear":   return op(manager, actor, Proto.msg(Proto.C_CLEAR));
            case "invite":  return player(manager, actor, args, Proto.C_INVITE, null);
            case "admin":   return player(manager, actor, args, Proto.C_SET_ROLE, "admin");
            case "unadmin": return player(manager, actor, args, Proto.C_SET_ROLE, "member");
            case "kick":    return player(manager, actor, args, Proto.C_KICK, null);
            case "transfer":return player(manager, actor, args, Proto.C_TRANSFER, null);
            case "seek": {
                if (args.length < 2) return List.of("用法：/mp seek <秒>");
                long seconds = parseLong(args[1], -1);
                if (seconds < 0) return List.of("秒数必须是不小于 0 的整数");
                JsonObject m = Proto.msg(Proto.C_SEEK);
                m.addProperty("pos", seconds * 1000L);
                return op(manager, actor, m);
            }
            case "repeat": {
                if (args.length < 2) return List.of("用法：/mp repeat <off|one|all>");
                JsonObject m = Proto.msg(Proto.C_REPEAT);
                m.addProperty("mode", args[1]);
                return op(manager, actor, m);
            }
            case "play": {
                if (args.length < 2) return List.of("用法：/mp play <歌单序号>");
                long index = parseLong(args[1], 0);
                if (index < 1) return List.of("序号从 1 开始");
                JsonObject m = Proto.msg(Proto.C_PLAY_INDEX);
                m.addProperty("index", index - 1);
                return op(manager, actor, m);
            }
            case "info":  return info(manager, actor);
            case "queue": return queue(manager, actor);
            case "list":
                if (!operator) return List.of("只有管理员可以查看全部房间");
                return list(manager);
            case "help": default: return help();
        }
    }

    private static List<String> op(RoomManager manager, UUID actor, JsonObject msg) {
        manager.handle(actor, msg);
        return List.of();
    }

    private static List<String> player(RoomManager manager, UUID actor, String[] args,
                                       String protoOp, String role) {
        if (args.length < 2) return List.of("用法：/mp " + args[0] + " <玩家>");
        JsonObject m = Proto.msg(protoOp);
        m.addProperty("target", args[1]);
        if (role != null) m.addProperty("role", role);
        return op(manager, actor, m);
    }

    private static List<String> info(RoomManager manager, UUID actor) {
        Room room = manager.roomOf(actor);
        if (room == null) {
            return List.of("你还没有加入一起听房间，使用 /mp create 创建或 /mp join <邀请码> 加入");
        }
        List<String> out = new ArrayList<>();
        out.add("房间：" + room.name + "  邀请码：" + room.code + "  循环：" + room.repeat().id());
        QueueEntry cur = room.current();
        out.add("正在播放：" + (cur == null ? "（无）"
                : cur.track.display() + "  " + fmt(room.positionMs()) + "/" + fmt(cur.track.durationMs)
                        + (room.isPlaying() ? " ▶" : " ⏸")));
        StringBuilder sb = new StringBuilder("成员（").append(room.members().size())
                .append("，收听中 ").append(room.listenerCount()).append("）：");
        boolean first = true;
        for (Member m : room.members()) {
            if (!first) sb.append("，");
            first = false;
            sb.append(m.name).append('[').append(m.role.id()).append(m.online ? "" : ",离线").append(']');
        }
        out.add(sb.toString());
        return out;
    }

    private static List<String> queue(RoomManager manager, UUID actor) {
        Room room = manager.roomOf(actor);
        if (room == null) return List.of("你还没有加入一起听房间");
        if (room.queue().isEmpty()) return List.of("一起听歌单为空");
        List<String> out = new ArrayList<>();
        out.add("一起听歌单（" + room.queue().size() + "）：");
        int i = 1;
        for (QueueEntry e : room.queue()) {
            out.add(i++ + ". " + e.track.display() + "  — " + e.requesterName);
        }
        return out;
    }

    private static List<String> list(RoomManager manager) {
        if (manager.rooms().isEmpty()) return List.of("当前没有一起听房间");
        List<String> out = new ArrayList<>();
        out.add("一起听房间：");
        for (Room r : manager.rooms()) {
            out.add(r.code + "  " + r.name + "  成员 " + r.members().size()
                    + "  队列 " + r.queue().size()
                    + "  " + (r.current() == null ? "空闲" : r.current().track.display()));
        }
        return out;
    }

    private static List<String> help() {
        return List.of(
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
                "点歌与切歌请在音乐播放器界面操作（需安装支持一起听的客户端 mod）");
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Throwable ignored) { return def; }
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
