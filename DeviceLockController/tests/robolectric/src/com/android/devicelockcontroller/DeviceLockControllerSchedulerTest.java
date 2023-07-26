/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.devicelockcontroller;

import static com.android.devicelockcontroller.DeviceLockControllerScheduler.DEVICE_CHECK_IN_WORK_NAME;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.PROVISION_PAUSED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.UNPROVISIONED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.provision.worker.DeviceCheckInWorker;
import com.android.devicelockcontroller.storage.GlobalParametersClient;

import com.google.common.util.concurrent.Futures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlarmManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public final class DeviceLockControllerSchedulerTest {
    public static final long PROVISION_PAUSED_MILLIS = TimeUnit.MINUTES.toMillis(
            DeviceLockControllerScheduler.PROVISION_PAUSED_MINUTES_DEFAULT);
    public static final Duration TEST_RETRY_CHECK_IN_DELAY = Duration.ofDays(30);
    private static final long TEST_NEXT_CHECK_IN_TIME_MILLIS = Duration.ofHours(10).toMillis();
    private static final long TEST_RESUME_PROVISION_TIME_MILLIS = Duration.ofHours(20).toMillis();
    private static final long TEST_CURRENT_TIME_MILLIS = Duration.ofHours(5).toMillis();
    private static final Clock TEST_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(TEST_CURRENT_TIME_MILLIS),
            ZoneOffset.UTC);
    private static final Duration TEST_POSITIVE_DELTA = Duration.ofHours(5);
    private static final Duration TEST_NEGATIVE_DELTA = Duration.ofHours(-5);
    DeviceLockControllerScheduler mScheduler;
    TestDeviceLockControllerApplication mTestApp;
    private GlobalParametersClient mClient;

    @Before
    public void setUp() throws Exception {
        mTestApp = ApplicationProvider.getApplicationContext();
        mScheduler = new DeviceLockControllerScheduler(mTestApp, TEST_CLOCK);
        mClient = GlobalParametersClient.getInstance();
        Configuration config = new Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .setExecutor(new SynchronousExecutor())
                .build();
        WorkManagerTestInitHelper.initializeTestWorkManager(mTestApp, config);
    }

    @Test
    public void correctExpectedToRunTime_retryCheckInExpected_positiveDelta_shouldUpdate() {
        // GIVEN device is unprovisioned
        DeviceStateController stateController = mTestApp.getStateController();
        when(stateController.getState()).thenReturn(UNPROVISIONED);

        // GIVEN retry check in is expected
        Futures.getUnchecked(mClient.setNextCheckInTimeMillis(TEST_NEXT_CHECK_IN_TIME_MILLIS));

        // WHEN time change happens
        mScheduler.correctExpectedToRunTime(TEST_POSITIVE_DELTA);

        // THEN next check in time should be updated
        long expectedToRunAfterChange =
                TEST_NEXT_CHECK_IN_TIME_MILLIS + TEST_POSITIVE_DELTA.toMillis();
        assertThat(Futures.getUnchecked(mClient.getNextCheckInTimeMillis())).isEqualTo(
                expectedToRunAfterChange);
    }

    @Test
    public void correctExpectedToRunTime_retryCheckInExpected_negativeDelta_shouldUpdate() {
        // GIVEN device is unprovisioned
        DeviceStateController stateController = mTestApp.getStateController();
        when(stateController.getState()).thenReturn(UNPROVISIONED);

        // GIVEN retry check in is expected
        Futures.getUnchecked(mClient.setNextCheckInTimeMillis(TEST_NEXT_CHECK_IN_TIME_MILLIS));

        // WHEN time change happens
        mScheduler.correctExpectedToRunTime(TEST_NEGATIVE_DELTA);

        // THEN next check in time should be updated
        long expectedToRunAfterChange =
                TEST_NEXT_CHECK_IN_TIME_MILLIS + TEST_NEGATIVE_DELTA.toMillis();
        assertThat(Futures.getUnchecked(mClient.getNextCheckInTimeMillis())).isEqualTo(
                expectedToRunAfterChange);
    }

    @Test
    public void correctExpectedToRunTime_resumeProvisionExpected_positiveDelta_shouldUpdate() {
        // GIVEN device is unprovisioned
        DeviceStateController stateController = mTestApp.getStateController();
        when(stateController.getState()).thenReturn(PROVISION_PAUSED);

        // GIVEN retry check in is expected
        Futures.getUnchecked(
                mClient.setResumeProvisionTimeMillis(TEST_RESUME_PROVISION_TIME_MILLIS));

        // WHEN time change happens
        mScheduler.correctExpectedToRunTime(TEST_POSITIVE_DELTA);

        // THEN next check in time should be updated
        long expectedToRunAfterChange =
                TEST_RESUME_PROVISION_TIME_MILLIS + TEST_POSITIVE_DELTA.toMillis();
        assertThat(Futures.getUnchecked(mClient.getResumeProvisionTimeMillis())).isEqualTo(
                expectedToRunAfterChange);
    }

    @Test
    public void correctExpectedToRunTime_resumeProvisionExpected_negativeDelta_shouldUpdate() {
        // GIVEN device is unprovisioned
        DeviceStateController stateController = mTestApp.getStateController();
        when(stateController.getState()).thenReturn(PROVISION_PAUSED);

        // GIVEN retry check in is expected
        Futures.getUnchecked(
                mClient.setResumeProvisionTimeMillis(TEST_RESUME_PROVISION_TIME_MILLIS));

        // WHEN time change happens
        mScheduler.correctExpectedToRunTime(TEST_NEGATIVE_DELTA);

        // THEN next check in time should be updated
        long expectedToRunAfterChange =
                TEST_RESUME_PROVISION_TIME_MILLIS + TEST_NEGATIVE_DELTA.toMillis();
        assertThat(Futures.getUnchecked(mClient.getResumeProvisionTimeMillis())).isEqualTo(
                expectedToRunAfterChange);
    }

    @Test
    public void scheduleResumeProvisionAlarm() {
        // GIVEN no alarm is scheduled and no expected resume time
        ShadowAlarmManager alarmManager = Shadows.shadowOf(
                mTestApp.getSystemService(AlarmManager.class));
        assertThat(alarmManager.peekNextScheduledAlarm()).isNull();
        assertThat(Futures.getUnchecked(mClient.getResumeProvisionTimeMillis())).isEqualTo(0);

        // WHEN resume provision alarm is scheduled
        mScheduler.scheduleResumeProvisionAlarm();

        // THEN correct alarm should be scheduled
        PendingIntent actualPendingIntent = alarmManager.peekNextScheduledAlarm().operation;
        assertThat(actualPendingIntent.isBroadcast()).isTrue();

        // THEN alarm should be scheduled at correct time
        long actualTriggerTime = alarmManager.peekNextScheduledAlarm().triggerAtTime;
        assertThat(actualTriggerTime).isEqualTo(
                SystemClock.elapsedRealtime() + PROVISION_PAUSED_MILLIS);

        // THEN expected trigger time should be stored in storage
        assertThat(Futures.getUnchecked(mClient.getResumeProvisionTimeMillis())).isEqualTo(
                TEST_CURRENT_TIME_MILLIS + PROVISION_PAUSED_MILLIS);

    }

    @Test
    public void rescheduleResumeProvisionAlarm() {
        // GIVEN no alarm is scheduled
        ShadowAlarmManager alarmManager = Shadows.shadowOf(
                mTestApp.getSystemService(AlarmManager.class));
        assertThat(alarmManager.peekNextScheduledAlarm()).isNull();

        // GIVEN expected resume time in storage
        Futures.getUnchecked(
                mClient.setResumeProvisionTimeMillis(TEST_RESUME_PROVISION_TIME_MILLIS));

        // WHEN resume provision alarm is rescheduled
        mScheduler.rescheduleResumeProvisionAlarm();

        // THEN correct alarm should be scheduled at correct time
        PendingIntent actualPendingIntent = alarmManager.peekNextScheduledAlarm().operation;
        assertThat(actualPendingIntent.isBroadcast()).isTrue();


        long expectedTriggerTime = SystemClock.elapsedRealtime()
                + (TEST_RESUME_PROVISION_TIME_MILLIS - TEST_CURRENT_TIME_MILLIS);
        assertThat(alarmManager.peekNextScheduledAlarm().triggerAtTime).isEqualTo(
                expectedTriggerTime);
    }

    @Test
    public void scheduleInitialCheckInWork() throws Exception {
        // GIVEN check-in work is not scheduled
        WorkManager workManager = WorkManager.getInstance(mTestApp);
        assertThat(workManager.getWorkInfosForUniqueWork(
                DEVICE_CHECK_IN_WORK_NAME).get()).isEmpty();

        // WHEN schedule initial check-in work
        mScheduler.scheduleInitialCheckInWork();

        // THEN check-in work should be scheduled
        List<WorkInfo> actualWorks = workManager.getWorkInfosForUniqueWork(
                DEVICE_CHECK_IN_WORK_NAME).get();
        assertThat(actualWorks.size()).isEqualTo(1);
        WorkInfo actualWorkInfo = actualWorks.get(0);
        assertThat(actualWorkInfo.getConstraints().getRequiredNetworkType()).isEqualTo(
                NetworkType.CONNECTED);
        assertThat(actualWorkInfo.getInitialDelayMillis()).isEqualTo(0);
    }

    @Test
    public void scheduleRetryCheckInWork() throws Exception {
        // GIVEN check-in work is not scheduled
        WorkManager workManager = WorkManager.getInstance(mTestApp);
        assertThat(workManager.getWorkInfosForUniqueWork(
                DEVICE_CHECK_IN_WORK_NAME).get()).isEmpty();

        // WHEN schedule retry check-in work
        mScheduler.scheduleRetryCheckInWork(TEST_RETRY_CHECK_IN_DELAY);

        // THEN retry check-in work should be scheduled
        List<WorkInfo> actualWorks = workManager.getWorkInfosForUniqueWork(
                DEVICE_CHECK_IN_WORK_NAME).get();
        assertThat(actualWorks.size()).isEqualTo(1);
        WorkInfo actualWorkInfo = actualWorks.get(0);
        assertThat(actualWorkInfo.getConstraints().getRequiredNetworkType()).isEqualTo(
                NetworkType.CONNECTED);
        assertThat(actualWorkInfo.getInitialDelayMillis()).isEqualTo(
                TEST_RETRY_CHECK_IN_DELAY.toMillis());

        // THEN expected trigger time is stored in storage
        long expectedTriggerTime = TEST_CURRENT_TIME_MILLIS + TEST_RETRY_CHECK_IN_DELAY.toMillis();
        assertThat(mClient.getNextCheckInTimeMillis().get()).isEqualTo(expectedTriggerTime);
    }

    @Test
    public void rescheduleRetryCheckInWork() throws Exception {
        // GIVEN check-in work is scheduled with original delay
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(DeviceCheckInWorker.class)
                        .setInitialDelay(TEST_RETRY_CHECK_IN_DELAY).build();
        WorkManager workManager = WorkManager.getInstance(mTestApp);
        workManager.enqueueUniqueWork(DEVICE_CHECK_IN_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request);

        // GIVEN expected trigger time
        mClient.setNextCheckInTimeMillis(TEST_NEXT_CHECK_IN_TIME_MILLIS).get();

        // WHEN reschedule retry check-in work
        mScheduler.rescheduleRetryCheckInWork();

        // THEN retry check-in work should be scheduled with correct delay
        List<WorkInfo> actualWorks = workManager.getWorkInfosForUniqueWork(
                DEVICE_CHECK_IN_WORK_NAME).get();
        assertThat(actualWorks.size()).isEqualTo(1);
        WorkInfo actualWorkInfo = actualWorks.get(0);
        assertThat(actualWorkInfo.getConstraints().getRequiredNetworkType()).isEqualTo(
                NetworkType.CONNECTED);

        long expectedDelay = TEST_NEXT_CHECK_IN_TIME_MILLIS - TEST_CURRENT_TIME_MILLIS;
        assertThat(actualWorkInfo.getInitialDelayMillis()).isEqualTo(expectedDelay);

    }
}
