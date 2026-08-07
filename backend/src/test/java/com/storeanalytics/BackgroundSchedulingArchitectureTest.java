package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.interpretation.config.WeeklySnapshotPlanningSchedulingConfiguration;
import com.storeanalytics.interpretation.config.WeeklySnapshotSchedulingConfiguration;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotJobWorker;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanner;
import com.storeanalytics.notification.config.NotificationFanoutSchedulingConfiguration;
import com.storeanalytics.notification.fanout.NotificationEventFanoutWorker;
import com.storeanalytics.sync.config.SyncWorkerSchedulingConfiguration;
import com.storeanalytics.report.config.ReportBackfillSchedulingConfiguration;
import com.storeanalytics.report.service.ReportBackfillJobWorker;
import com.storeanalytics.sync.service.SyncJobWorker;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

class BackgroundSchedulingArchitectureTest {

    private static final Set<ApplicationRole> WORKER_ROLES = EnumSet.of(
            ApplicationRole.WORKER,
            ApplicationRole.COMBINED
    );

    @Test
    void everyScheduledComponentIsOwnedByWorkerRoles() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> missingRoleGuard = new ArrayList<>();
        List<String> invalidRoleGuard = new ArrayList<>();
        List<String> missingSchedulerBulkhead = new ArrayList<>();
        for (var component : scanner.findCandidateComponents("com.storeanalytics")) {
            Class<?> componentType = Class.forName(component.getBeanClassName());
            List<Method> scheduledMethods = Arrays.stream(componentType.getDeclaredMethods())
                    .filter(method -> AnnotatedElementUtils.hasAnnotation(
                            method,
                            Scheduled.class
                    ))
                    .toList();
            if (scheduledMethods.isEmpty()) {
                continue;
            }
            scheduledMethods.forEach(method -> {
                Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(
                        method,
                        Scheduled.class
                );
                if (scheduled == null || scheduled.scheduler().isBlank()) {
                    missingSchedulerBulkhead.add(
                            componentType.getSimpleName() + "#" + method.getName()
                    );
                }
            });
            ConditionalOnApplicationRole roleGuard =
                    AnnotatedElementUtils.findMergedAnnotation(
                            componentType,
                            ConditionalOnApplicationRole.class
                    );
            if (roleGuard == null) {
                scheduledMethods.forEach(method -> missingRoleGuard.add(
                        componentType.getSimpleName() + "#" + method.getName()
                ));
                continue;
            }
            Set<ApplicationRole> configuredRoles = EnumSet.copyOf(
                    Arrays.asList(roleGuard.value())
            );
            if (!configuredRoles.equals(WORKER_ROLES)) {
                invalidRoleGuard.add(
                        componentType.getSimpleName() + "=" + configuredRoles
                );
            }
        }

        assertThat(missingRoleGuard)
                .as("scheduled methods without an application-role guard")
                .isEmpty();
        assertThat(invalidRoleGuard)
                .as("scheduled components not owned exactly by WORKER and COMBINED")
                .isEmpty();
        assertThat(missingSchedulerBulkhead)
                .as("scheduled methods using the shared default scheduler")
                .isEmpty();
    }

    @Test
    void syncJobWorkerUsesDedicatedSingleThreadScheduler()
            throws NoSuchMethodException {
        Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(
                SyncJobWorker.class.getDeclaredMethod("processNextStep"),
                Scheduled.class
        );

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo(
                SyncWorkerSchedulingConfiguration.SYNC_WORKER_SCHEDULER
        );
    }

    @Test
    void reportBackfillUsesDedicatedSingleThreadScheduler()
            throws NoSuchMethodException {
        Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(
                ReportBackfillJobWorker.class.getDeclaredMethod("processNextStep"),
                Scheduled.class
        );

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo(
                ReportBackfillSchedulingConfiguration.REPORT_BACKFILL_SCHEDULER
        );
    }

    @Test
    void weeklySnapshotWorkerSeparatesExecutionAndHeartbeatSchedulers()
            throws NoSuchMethodException {
        Scheduled execution = AnnotatedElementUtils.findMergedAnnotation(
                WeeklySnapshotJobWorker.class.getDeclaredMethod("processNext"),
                Scheduled.class
        );
        Scheduled heartbeat = AnnotatedElementUtils.findMergedAnnotation(
                WeeklySnapshotJobWorker.class.getDeclaredMethod("heartbeat"),
                Scheduled.class
        );

        assertThat(execution).isNotNull();
        assertThat(execution.scheduler()).isEqualTo(
                WeeklySnapshotSchedulingConfiguration.SNAPSHOT_WORKER_SCHEDULER
        );
        assertThat(heartbeat).isNotNull();
        assertThat(heartbeat.scheduler()).isEqualTo(
                WeeklySnapshotSchedulingConfiguration.SNAPSHOT_HEARTBEAT_SCHEDULER
        );
    }

    @Test
    void notificationFanoutUsesDedicatedScheduler() throws NoSuchMethodException {
        Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(
                NotificationEventFanoutWorker.class.getDeclaredMethod("processNext"),
                Scheduled.class
        );

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo(
                NotificationFanoutSchedulingConfiguration
                        .NOTIFICATION_FANOUT_SCHEDULER
        );
    }

    @Test
    void weeklySnapshotPlannerUsesDedicatedControlScheduler()
            throws NoSuchMethodException {
        Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(
                WeeklySnapshotPlanner.class.getDeclaredMethod("reconcile"),
                Scheduled.class
        );

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo(
                WeeklySnapshotPlanningSchedulingConfiguration
                        .SNAPSHOT_PLANNING_SCHEDULER
        );
    }
}
