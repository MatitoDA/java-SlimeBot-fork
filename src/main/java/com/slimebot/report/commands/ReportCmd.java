package com.slimebot.report.commands;

import com.slimebot.main.Main;
import com.slimebot.report.assets.Report;
import com.slimebot.report.assets.Type;
import com.slimebot.utils.Checks;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class ReportCmd extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        super.onSlashCommandInteraction(event);

        if (!(event.getName().equals("report"))) {return;}
        if (Main.blocklist.contains(event.getMember())) {
            EmbedBuilder embedBuilder = new EmbedBuilder()
                    .setTimestamp(LocalDateTime.now().atZone(ZoneId.systemDefault()))
                    .setColor(Main.embedColor(event.getGuild().getId()))
                    .setTitle(":exclamation: Error: Blocked")
                    .setDescription("Du wurdest gesperrt, so dass du keine Reports mehr erstellen kannst");
            event.replyEmbeds(embedBuilder.build()).setEphemeral(true).queue();
            return;
        }

        int reportID = Main.reports.size() + 1;

        OptionMapping user = event.getOption("user");
        OptionMapping description = event.getOption("beschreibung");

        Main.reports.add(Report.newReport(reportID, Type.USER, user.getAsMember(), event.getMember(), description.getAsString()));

        EmbedBuilder embedBuilder = new EmbedBuilder()
                .setTimestamp(LocalDateTime.now().atZone(ZoneId.systemDefault()))
                .setColor(Main.embedColor(event.getGuild().getId()))
                .setTitle(":white_check_mark: Report Erfolgreich")
                .setDescription(user.getAsMentionable().getAsMention() + " wurde erfolgreich gemeldet");
        event.replyEmbeds(embedBuilder.build()).queue();
        Report.log(reportID, event.getGuild().getId());




    }
}
