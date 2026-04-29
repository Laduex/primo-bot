package dev.saseq.primobot.sales;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesReportSchedulerServiceTest {

    @Mock
    private JDA jda;

    @Mock
    private SalesReportConfigStore configStore;

    @Mock
    private SalesReportExecutorService executorService;

    @Mock
    private Guild guild;

    @Test
    void scheduledUpdateReservesSlotBeforeSending() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Manila"));
        String slot = now.toLocalTime().withSecond(0).withNano(0).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        String today = now.toLocalDate().toString();

        SalesReportConfig config = new SalesReportConfig();
        config.setEnabled(true);
        config.setTimezone("Asia/Manila");
        config.setTimes(new java.util.ArrayList<>(java.util.List.of(slot)));
        config.setOverviewTime("");

        when(configStore.getSnapshot()).thenReturn(config);
        when(jda.getGuildById("1478671501338214410")).thenReturn(guild);
        when(executorService.execute(eq(guild), any(SalesReportConfig.class), eq(null), eq(null), eq(false)))
                .thenReturn(new SalesReportExecutorService.DispatchResult(
                        SalesReportExecutorService.DispatchStatus.SENT,
                        "1493429088176832662",
                        "",
                        "Sent",
                        3,
                        0
                ));

        SalesReportSchedulerService scheduler = new SalesReportSchedulerService(
                jda,
                configStore,
                executorService,
                "1478671501338214410"
        );

        List<SalesReportConfig> persistedSnapshots = new ArrayList<>();
        doAnswer(invocation -> {
            SalesReportConfig persisted = invocation.getArgument(0);
            SalesReportConfig snapshot = new SalesReportConfig();
            snapshot.setLastRunDateBySlot(new LinkedHashMap<>(persisted.getLastRunDateBySlot()));
            persistedSnapshots.add(snapshot);
            return snapshot;
        }).when(configStore).replaceAndPersist(any(SalesReportConfig.class));

        scheduler.runSalesTick();

        verify(configStore, times(1)).replaceAndPersist(any(SalesReportConfig.class));
        assertEquals(today, persistedSnapshots.get(0).getLastRunDateBySlot().get("update:" + slot));
    }

    @Test
    void failedScheduledUpdateClearsReservedSlot() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Manila"));
        String slot = now.toLocalTime().withSecond(0).withNano(0).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));

        SalesReportConfig config = new SalesReportConfig();
        config.setEnabled(true);
        config.setTimezone("Asia/Manila");
        config.setTimes(new java.util.ArrayList<>(java.util.List.of(slot)));
        config.setOverviewTime("");

        when(configStore.getSnapshot()).thenReturn(config);
        when(jda.getGuildById("1478671501338214410")).thenReturn(guild);
        when(executorService.execute(eq(guild), any(SalesReportConfig.class), eq(null), eq(null), eq(false)))
                .thenReturn(new SalesReportExecutorService.DispatchResult(
                        SalesReportExecutorService.DispatchStatus.SEND_FAILED,
                        "1493429088176832662",
                        "",
                        "boom",
                        0,
                        0
                ));

        SalesReportSchedulerService scheduler = new SalesReportSchedulerService(
                jda,
                configStore,
                executorService,
                "1478671501338214410"
        );

        List<SalesReportConfig> persistedSnapshots = new ArrayList<>();
        doAnswer(invocation -> {
            SalesReportConfig persisted = invocation.getArgument(0);
            SalesReportConfig snapshot = new SalesReportConfig();
            snapshot.setLastRunDateBySlot(new LinkedHashMap<>(persisted.getLastRunDateBySlot()));
            persistedSnapshots.add(snapshot);
            return snapshot;
        }).when(configStore).replaceAndPersist(any(SalesReportConfig.class));

        scheduler.runSalesTick();

        verify(configStore, times(2)).replaceAndPersist(any(SalesReportConfig.class));
        assertTrue(persistedSnapshots.get(0).getLastRunDateBySlot().containsKey("update:" + slot));
        assertFalse(persistedSnapshots.get(1).getLastRunDateBySlot().containsKey("update:" + slot));
    }
}
