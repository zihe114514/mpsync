package yun.shan.mpsync.forge;

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
import yun.shan.mpsync.command.MpTextCommands;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * {@code /mp <args...>} on Forge — same surface as Fabric/Paper via {@link MpTextCommands}.
 */
public final class ForgeCommands {

    private ForgeCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> root(Predicate<CommandSourceStack> isOperator) {
        return Commands.literal("mp")
                .executes(ctx -> run(ctx, isOperator, new String[0]))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(ForgeCommands::suggest)
                        .executes(ctx -> run(ctx, isOperator,
                                split(StringArgumentType.getString(ctx, "args")))));
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

    private static int run(CommandContext<CommandSourceStack> ctx,
                           Predicate<CommandSourceStack> isOperator, String[] args)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean operator = isOperator.test(ctx.getSource());
        for (String line : MpTextCommands.run(player.getUUID(), operator, args)) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
