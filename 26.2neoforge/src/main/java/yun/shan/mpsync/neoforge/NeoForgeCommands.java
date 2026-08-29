package yun.shan.mpsync.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import yun.shan.mpsync.command.MpTextCommands;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** {@code /mp} on NeoForge 26.2 — Permission API matches Fabric 26.2. */
public final class NeoForgeCommands {

    private NeoForgeCommands() {}

    private static final Permission LEVEL_2 = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    public static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("mp")
                .executes(ctx -> run(ctx, new String[0]))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(NeoForgeCommands::suggest)
                        .executes(ctx -> run(ctx, split(StringArgumentType.getString(ctx, "args")))));
    }

    private static CompletableFuture<Suggestions> suggest(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String[] words = split(remaining);
        boolean trailingSpace = remaining.endsWith(" ");
        String[] forSuggest = trailingSpace ? append(words, "") : words;
        List<String> names = ctx.getSource().getServer() == null ? List.of()
                : List.of(ctx.getSource().getServer().getPlayerNames());
        int start = remaining.length() - (trailingSpace ? 0 : lastWordLength(remaining));
        SuggestionsBuilder at = builder.createOffset(builder.getStart() + start);
        for (String s : MpTextCommands.suggest(forSuggest, names)) at.suggest(s);
        return at.buildFuture();
    }

    private static int lastWordLength(String s) {
        int i = s.lastIndexOf(' ');
        return i < 0 ? s.length() : s.length() - i - 1;
    }

    private static String[] append(String[] arr, String extra) {
        String[] out = new String[arr.length + 1];
        System.arraycopy(arr, 0, out, 0, arr.length);
        out[arr.length] = extra;
        return out;
    }

    private static String[] split(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String[] args)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean operator = ctx.getSource().permissions().hasPermission(LEVEL_2);
        for (String line : MpTextCommands.run(player.getUUID(), operator, args)) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
