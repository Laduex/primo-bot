package dev.saseq.primobot.meta;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetaUnreadSchedulerServiceTest {

    @Mock
    private JDA jda;

    @Mock
    private MetaUnreadConfigStore configStore;

    @Mock
    private MetaUnreadExecutorService executorService;

    @Mock
    private Guild guild;

    @Test
    void scheduledTickRunsWhenEnabledAndDue() {
        MetaUnreadConfig config = new MetaUnreadConfig();
        config.setEnabled(true);
        config.setIntervalMinutes(15);
        config.setLastRunAtEpochMs(0L);
        config.setTargetChannelId("1494175620287041536");

        when(configStore.getSnapshot()).thenReturn(config);
        when(jda.getGuildById("1478671501338214410")).thenReturn(guild);
        when(executorService.execute(eq(guild), any(MetaUnreadConfig.class))).thenReturn(
                new MetaUnreadExecutorService.DispatchResult(
                        MetaUnreadExecutorService.DispatchStatus.NO_UNREAD,
                        "1494175620287041536",
                        "No unread conversations",
                        2,
                        0,
                        0,
                        0
                )
        );

        MetaUnreadSchedulerService scheduler = new MetaUnreadSchedulerService(
                jda,
                configStore,
                executorService,
                "1478671501338214410"
        );

        scheduler.runMetaUnreadTick();

        verify(executorService).execute(eq(guild), any(MetaUnreadConfig.class));
        verify(configStore).replaceAndPersist(argThat(updated -> updated.getLastRunAtEpochMs() > 0L));
    }

    @Test
    void scheduledTickSkipsWhenNotDue() {
        MetaUnreadConfig config = new MetaUnreadConfig();
        config.setEnabled(true);
        config.setIntervalMinutes(15);
        config.setLastRunAtEpochMs(System.currentTimeMillis());

        when(configStore.getSnapshot()).thenReturn(config);

        MetaUnreadSchedulerService scheduler = new MetaUnreadSchedulerService(
                jda,
                configStore,
                executorService,
                "1478671501338214410"
        );

        scheduler.runMetaUnreadTick();

        verify(executorService, never()).execute(any(), any());
        verify(configStore, never()).replaceAndPersist(any());
    }

    @Test
    void runNowWorksEvenWhenDisabled() {
        MetaUnreadConfig config = new MetaUnreadConfig();
        config.setEnabled(false);
        config.setIntervalMinutes(15);
        config.setTargetChannelId("1494175620287041536");

        when(configStore.getSnapshot()).thenReturn(config);
        when(executorService.execute(eq(guild), any(MetaUnreadConfig.class))).thenReturn(
                new MetaUnreadExecutorService.DispatchResult(
                        MetaUnreadExecutorService.DispatchStatus.NO_UNREAD,
                        "1494175620287041536",
                        "No unread conversations",
                        2,
                        0,
                        0,
                        0
                )
        );

        MetaUnreadSchedulerService scheduler = new MetaUnreadSchedulerService(
                jda,
                configStore,
                executorService,
                ""
        );

        scheduler.runNow(guild);

        verify(executorService).execute(eq(guild), any(MetaUnreadConfig.class));
        verify(configStore).replaceAndPersist(argThat(updated -> updated.getLastRunAtEpochMs() > 0L));
    }
}
