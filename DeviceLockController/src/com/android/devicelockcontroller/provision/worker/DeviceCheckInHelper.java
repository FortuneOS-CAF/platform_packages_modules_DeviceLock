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

import static com.android.devicelockcontroller.common.DeviceLockConstants.DEVICE_ID_TYPE_IMEI;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DEVICE_ID_TYPE_MEID;
import static com.android.devicelockcontroller.common.DeviceLockConstants.EXTRA_MANDATORY_PROVISION;
import static com.android.devicelockcontroller.common.DeviceLockConstants.EXTRA_PROVISIONING_TYPE;
import static com.android.devicelockcontroller.common.DeviceLockConstants.READY_FOR_PROVISION;
import static com.android.devicelockcontroller.common.DeviceLockConstants.RETRY_CHECK_IN;
import static com.android.devicelockcontroller.common.DeviceLockConstants.STATUS_UNSPECIFIED;
import static com.android.devicelockcontroller.common.DeviceLockConstants.STOP_CHECK_IN;
import static com.android.devicelockcontroller.common.DeviceLockConstants.TOTAL_DEVICE_ID_TYPES;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.PROVISIONING_SUCCESS;

import android.content.Context;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.android.devicelockcontroller.DeviceLockControllerApplication;
import com.android.devicelockcontroller.R;
import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.StateTransitionException;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.ProvisioningConfiguration;
import com.android.devicelockcontroller.setup.SetupParametersClient;
import com.android.devicelockcontroller.setup.UserPreferences;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Helper class to perform the device check-in process with device lock backend server
 */
public final class DeviceCheckInHelper extends AbstractDeviceCheckInHelper {
    @VisibleForTesting
    public static final String CHECK_IN_WORK_NAME = "checkIn";
    private static final String TAG = "DeviceCheckInHelper";
    private static final int CHECK_IN_INTERVAL_HOURS = 1;
    private final Context mAppContext;
    private final TelephonyManager mTelephonyManager;

    public DeviceCheckInHelper(Context appContext) {
        mAppContext = appContext;
        mTelephonyManager = mAppContext.getSystemService(TelephonyManager.class);
    }

    /**
     * Enqueue the DeviceCheckIn work request to WorkManager
     *
     * @param isExpedited If true, the work request should be expedited;
     */
    @Override
    public void enqueueDeviceCheckInWork(boolean isExpedited) {
        enqueueDeviceCheckInWork(isExpedited, Duration.ZERO);
    }

    /**
     * Enqueue the DeviceCheckIn work request to WorkManager
     *
     * @param isExpedited If true, the work request should be expedited;
     * @param delay       The duration that need to be delayed before performing check-in.
     */
    private void enqueueDeviceCheckInWork(boolean isExpedited, Duration delay) {
        LogUtil.i(TAG, "enqueueDeviceCheckInWork with delay: " + delay);
        final OneTimeWorkRequest.Builder builder =
                new OneTimeWorkRequest.Builder(DeviceCheckInWorker.class)
                        .setConstraints(
                                new Constraints.Builder().setRequiredNetworkType(
                                        NetworkType.CONNECTED).build())
                        .setInitialDelay(delay)
                        .setBackoffCriteria(BackoffPolicy.LINEAR,
                                Duration.ofHours(CHECK_IN_INTERVAL_HOURS));
        if (isExpedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        WorkManager.getInstance(mAppContext).enqueueUniqueWork(CHECK_IN_WORK_NAME,
                ExistingWorkPolicy.REPLACE, builder.build());
    }


    @Override
    @NonNull
    ArraySet<DeviceId> getDeviceUniqueIds() {
        final int deviceIdTypeBitmap = mAppContext.getResources().getInteger(
                R.integer.device_id_type_bitmap);
        if (deviceIdTypeBitmap < 0) {
            LogUtil.e(TAG, "getDeviceId: Cannot get device_id_type_bitmap");
            return new ArraySet<>();
        }

        return getDeviceAvailableUniqueIds(deviceIdTypeBitmap);
    }

    @VisibleForTesting
    ArraySet<DeviceId> getDeviceAvailableUniqueIds(int deviceIdTypeBitmap) {

        final int totalSlotCount = mTelephonyManager.getActiveModemCount();
        final int maximumIdCount = TOTAL_DEVICE_ID_TYPES * totalSlotCount;
        final ArraySet<DeviceId> deviceIds = new ArraySet<>(maximumIdCount);
        if (maximumIdCount == 0) return deviceIds;

        for (int i = 0; i < totalSlotCount; i++) {
            if ((deviceIdTypeBitmap & (1 << DEVICE_ID_TYPE_IMEI)) != 0) {
                final String imei = mTelephonyManager.getImei(i);

                if (imei != null) {
                    deviceIds.add(new DeviceId(DEVICE_ID_TYPE_IMEI, imei));
                }
            }

            if ((deviceIdTypeBitmap & (1 << DEVICE_ID_TYPE_MEID)) != 0) {
                final String meid = mTelephonyManager.getMeid(i);

                if (meid != null) {
                    deviceIds.add(new DeviceId(DEVICE_ID_TYPE_MEID, meid));
                }
            }
        }

        return deviceIds;
    }

    @Override
    @NonNull
    String getCarrierInfo() {
        // TODO(b/267507927): Figure out if we need carrier info of all sims.
        return mTelephonyManager.getSimOperator();
    }

    @Override
    boolean handleGetDeviceCheckInStatusResponse(
            @NonNull GetDeviceCheckInStatusGrpcResponse response) {
        UserPreferences.setRegisteredDeviceId(mAppContext,
                response.getRegisteredDeviceIdentifier());
        LogUtil.d(TAG, "check in succeed: " + response.getDeviceCheckInStatus());
        switch (response.getDeviceCheckInStatus()) {
            case READY_FOR_PROVISION:
                return handleProvisionReadyResponse(
                        response,
                        ((DeviceLockControllerApplication) mAppContext.getApplicationContext())
                                .getStateController());
            case RETRY_CHECK_IN:
                Instant nextCheckinTime = response.getNextCheckInTime();

                final Duration delay = Duration.between(Instant.now(),
                        Instant.ofEpochSecond(
                                nextCheckinTime.getEpochSecond(),
                                nextCheckinTime.getNano()));
                //TODO: Figure out whether there should be a minimum delay?
                if (delay.isNegative()) {
                    LogUtil.w(TAG, "Next check in date is not in the future");
                    return false;
                }
                enqueueDeviceCheckInWork(false, delay);
                return true;
            case STOP_CHECK_IN:
                UserPreferences.setNeedCheckIn(mAppContext, false);
                return true;
            case STATUS_UNSPECIFIED:
            default:
                // fall through
        }
        return false;
    }

    @VisibleForTesting
    boolean handleProvisionReadyResponse(
            @NonNull GetDeviceCheckInStatusGrpcResponse response,
            DeviceStateController stateController) {
        UserPreferences.setProvisionForced(mAppContext, response.isProvisionForced());
        final ProvisioningConfiguration configuration = response.getProvisioningConfig();
        if (configuration == null) {
            LogUtil.e(TAG, "Provisioning Configuration is not provided by server!");
            return false;
        }
        final Bundle provisionBundle = configuration.toBundle();
        provisionBundle.putInt(EXTRA_PROVISIONING_TYPE, response.getProvisioningType());
        provisionBundle.putBoolean(EXTRA_MANDATORY_PROVISION,
                response.isProvisioningMandatory());
        Futures.getUnchecked(
                SetupParametersClient.getInstance().createPrefs(provisionBundle));
        FutureCallback<Void> futureCallback = new FutureCallback<>() {
            @Override
            public void onSuccess(Void result) {
                LogUtil.i(TAG,
                        String.format(Locale.US,
                                "State transition succeeded for event: %s",
                                DeviceStateController.eventToString(PROVISIONING_SUCCESS)));
            }

            @Override
            public void onFailure(Throwable t) {
                //TODO: Reset the state to where it can successfully transition.
                LogUtil.e(TAG,
                        String.format(Locale.US,
                                "State transition failed for event: %s",
                                DeviceStateController.eventToString(PROVISIONING_SUCCESS)), t);
            }
        };
        UserPreferences.setNeedCheckIn(mAppContext, false);
        mAppContext.getMainExecutor().execute(
                () -> {
                    try {
                        Futures.addCallback(
                                stateController.setNextStateForEvent(PROVISIONING_SUCCESS),
                                futureCallback, MoreExecutors.directExecutor());
                    } catch (StateTransitionException e) {
                        //TODO: Reset the state to where it can successfully transition.
                        LogUtil.e(TAG,
                                String.format(Locale.US,
                                        "State transition failed for event: %s",
                                        DeviceStateController.eventToString(PROVISIONING_SUCCESS)),
                                e);
                    }
                });

        // We are only handling the non-mandatory provision case here. For mandatory
        // provision, we will handle when receiving the intent after SUW completed.
        if (!response.isProvisioningMandatory()) {
            // TODO(b/272497885): Schedule to launch the provision activity at some time.
        }
        return true;
    }
}
