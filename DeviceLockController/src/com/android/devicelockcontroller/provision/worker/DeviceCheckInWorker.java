/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.devicelockcontroller.provision.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.WorkerParameters;

import com.android.devicelockcontroller.FcmRegistrationTokenProvider;
import com.android.devicelockcontroller.policy.PolicyObjectsProvider;
import com.android.devicelockcontroller.provision.grpc.DeviceCheckInClient;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;
import com.android.devicelockcontroller.schedule.DeviceLockControllerScheduler;
import com.android.devicelockcontroller.schedule.DeviceLockControllerSchedulerProvider;
import com.android.devicelockcontroller.stats.StatsLogger;
import com.android.devicelockcontroller.stats.StatsLoggerProvider;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.time.Duration;

/**
 * A worker class dedicated to execute the check-in operation for device lock program.
 */
public final class DeviceCheckInWorker extends AbstractCheckInWorker {

    private final AbstractDeviceCheckInHelper mCheckInHelper;
    private final FcmRegistrationTokenProvider mFcmRegistrationTokenProvider;

    private final StatsLogger mStatsLogger;

    @VisibleForTesting
    static final Duration RETRY_ON_FAILURE_DELAY = Duration.ofDays(1);

    public DeviceCheckInWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams, ListeningExecutorService executorService) {
        this(context, workerParams, new DeviceCheckInHelper(context),
                (FcmRegistrationTokenProvider) context.getApplicationContext(),
                /* client= */ null,
                executorService);
    }

    @VisibleForTesting
    DeviceCheckInWorker(@NonNull Context context, @NonNull WorkerParameters workerParameters,
            AbstractDeviceCheckInHelper helper, FcmRegistrationTokenProvider tokenProvider,
            DeviceCheckInClient client, ListeningExecutorService executorService) {
        super(context, workerParameters, client, executorService);
        mFcmRegistrationTokenProvider = tokenProvider;
        mCheckInHelper = helper;
        StatsLoggerProvider loggerProvider =
                (StatsLoggerProvider) context.getApplicationContext();
        mStatsLogger = loggerProvider.getStatsLogger();
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        DeviceLockControllerSchedulerProvider schedulerProvider =
                (DeviceLockControllerSchedulerProvider) mContext;
        DeviceLockControllerScheduler scheduler =
                schedulerProvider.getDeviceLockControllerScheduler();
        return Futures.transformAsync(
                mExecutorService.submit(mCheckInHelper::getDeviceUniqueIds),
                deviceIds -> {
                    if (deviceIds.isEmpty()) {
                        LogUtil.w(TAG, "CheckIn failed. No device identifier available!");
                        // Similarly to STOP_CHECK_IN, finalize the device (without reporting it
                        // to the backend, since it's not part of the financing program).
                        final ListenableFuture<Void> finalizeDeviceFuture =
                                ((PolicyObjectsProvider) mContext).getFinalizationController()
                                        .finalizeNotEnrolledDevice();
                        return Futures.transformAsync(finalizeDeviceFuture,
                                unused -> Futures.immediateFuture(Result.failure()),
                                mExecutorService);
                    }
                    String carrierInfo = mCheckInHelper.getCarrierInfo();
                    ListenableFuture<String> fcmRegistrationToken =
                            mFcmRegistrationTokenProvider.getFcmRegistrationToken();
                    return Futures.whenAllSucceed(mClient, fcmRegistrationToken).call(() -> {
                        DeviceCheckInClient client = Futures.getDone(mClient);
                        String fcmToken = Futures.getDone(fcmRegistrationToken);
                        GetDeviceCheckInStatusGrpcResponse response =
                                client.getDeviceCheckInStatus(
                                        deviceIds, carrierInfo, fcmToken);
                        mStatsLogger.logGetDeviceCheckInStatus();
                        if (response.hasRecoverableError()) {
                            LogUtil.w(TAG, "Check-in failed w/ recoverable error " + response
                                    + "\nRetrying...");
                            mStatsLogger.logCheckInRetry(
                                    StatsLogger.CheckInRetryReason.RPC_FAILURE);
                            return Result.retry();
                        }
                        if (response.isSuccessful()) {
                            boolean isResponseHandlingSuccessful = mCheckInHelper
                                    .handleGetDeviceCheckInStatusResponse(response, scheduler,
                                            fcmToken);
                            if (isResponseHandlingSuccessful) {
                                mStatsLogger.logSuccessfulCheckIn();
                            }
                            return isResponseHandlingSuccessful ? Result.success() : Result.retry();
                        }

                        if (response.isInterrupted()) {
                            LogUtil.d(TAG, "Check-in interrupted");
                            return Result.failure();
                        }

                        LogUtil.e(TAG, "CheckIn failed: " + response + "\nRetry check-in in: "
                                + RETRY_ON_FAILURE_DELAY);
                        scheduler.scheduleRetryCheckInWork(RETRY_ON_FAILURE_DELAY);
                        mStatsLogger.logCheckInRetry(
                                StatsLogger.CheckInRetryReason.RPC_FAILURE);
                        return Result.failure();
                    }, mExecutorService);
                }, mExecutorService);
    }
}
