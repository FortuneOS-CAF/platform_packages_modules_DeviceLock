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

import java.time.Duration;

/** The abstraction of {@link DeviceLockControllerScheduler} */
public abstract class AbstractDeviceLockControllerScheduler {

    /**
     * Correct the stored expected to run time based on the time change delta.
     *
     * @param delta The duration from time before change to time after change. This value could be
     *              negative.
     */
    public abstract void correctExpectedToRunTime(Duration delta);

    /**
     * Schedule an alarm to resume the provision flow.
     */
    public abstract void scheduleResumeProvisionAlarm();

    /**
     * Reschedule the alarm to resume provision based on the stored expected to run time.
     */
    public abstract void rescheduleResumeProvisionAlarm();

    /**
     * Schedule the initial check-in work when device first boot.
     */
    public abstract void scheduleInitialCheckInWork();

    /**
     * Schedule the retry check-in work with a delay.
     *
     * @param delay The delayed duration to wait for performing retry check-in work.
     */
    public abstract void scheduleRetryCheckInWork(Duration delay);

    /**
     * Reschedule retry check-in work bassed on the stored expected to run time.
     */
    public abstract void rescheduleRetryCheckInWork();
}
