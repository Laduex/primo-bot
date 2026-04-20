package dev.saseq.primobot.config;

import dev.saseq.primobot.commands.PrimoCommands;
import dev.saseq.primobot.commands.PrimoSlashCommandListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrimoBotConfig {

    @Bean
    public JDA jda(@Value("${DISCORD_TOKEN:}") String token,
                   @Value("${DISCORD_GUILD_ID:}") String defaultGuildId,
                   PrimoSlashCommandListener primoSlashCommandListener) throws InterruptedException {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("The environment variable DISCORD_TOKEN is not set.");
        }

        JDA jda = JDABuilder.createDefault(token)
                .addEventListeners(primoSlashCommandListener)
                .build()
                .awaitReady();

        var vatCommand = PrimoCommands.buildVatSlashCommand();
        var orderCommand = PrimoCommands.buildOrderSlashCommand();
        if (defaultGuildId != null && !defaultGuildId.isBlank()) {
            Guild guild = jda.getGuildById(defaultGuildId);
            if (guild != null) {
                syncGuildCommands(guild, vatCommand, orderCommand);
                return jda;
            }
        }

        jda.upsertCommand(vatCommand).queue();
        jda.upsertCommand(orderCommand).queue();
        jda.retrieveCommands().queue(commands ->
                commands.stream()
                        .filter(command -> "primo".equals(command.getName()))
                        .forEach(command -> command.delete().queue())
        );
        return jda;
    }

    private void syncGuildCommands(Guild guild,
                                   net.dv8tion.jda.api.interactions.commands.build.CommandData vatCommand,
                                   net.dv8tion.jda.api.interactions.commands.build.CommandData orderCommand) {
        guild.upsertCommand(vatCommand).queue();
        guild.upsertCommand(orderCommand).queue();
        guild.retrieveCommands().queue(commands ->
                commands.stream()
                        .filter(command -> "primo".equals(command.getName()))
                        .forEach(command -> command.delete().queue())
        );
    }
}
