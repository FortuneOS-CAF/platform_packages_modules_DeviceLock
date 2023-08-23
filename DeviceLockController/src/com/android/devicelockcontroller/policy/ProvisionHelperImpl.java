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

package com.android.devicelockcontroller.policy;

import static androidx.work.WorkInfo.State.FAILED;
import static androidx.work.WorkInfo.State.SUCCEEDED;

import static com.android.devicelockcontroller.common.DeviceLockConstants.EXTRA_KIOSK_PACKAGE;
import static com.android.devicelockcontroller.policy.ProvisionStateController.ProvisionEvent.PROVISION_KIOSK;
import static com.android.devicelockcontroller.policy.ProvisionStateController.ProvisionEvent.PROVISION_PAUSE;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.android.devicelockcontroller.DeviceLockControllerApplication;
import com.android.devicelockcontroller.DeviceLockControllerScheduler;
import com.android.devicelockcontroller.activities.DeviceLockNotificationManager;
import com.android.devicelockcontroller.activities.ProvisioningProgress;
import com.android.devicelockcontroller.activities.ProvisioningProgressController;
import com.android.devicelockcontroller.provision.worker.PauseProvisioningWorker;
import com.android.devicelockcontroller.receivers.ResumeProvisionReceiver;
import com.android.devicelockcontroller.storage.GlobalParametersClient;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An implementation of {@link ProvisionHelper}.
 */
public final class ProvisionHelperImpl implements ProvisionHelper {

    private static final String SETUP_PLAY_INSTALL_TASKS_NAME =
            "devicelock_setup_play_install_tasks";
    private static final String TAG = "ProvisionHelperImpl";

    private final Context mContext;
    private final ProvisionStateController mStateController;
    private static final ExecutorService sExecutor = Executors.newCachedThreadPool();

    public ProvisionHelperImpl(Context context, ProvisionStateController stateController) {
        mContext = context;
        mStateController = stateController;
    }

    @Override
    public void pauseProvision() {
        Futures.addCallback(
                Futures.transformAsync(
                        GlobalParametersClient.getInstance().setProvisionForced(true),
                        unused -> mStateController.setNextStateForEvent(PROVISION_PAUSE),
                        sExecutor),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(Void unused) {
                        createNotification();
                        WorkManager workManager = WorkManager.getInstance(mContext);
                        PauseProvisioningWorker.reportProvisionPausedByUser(workManager);
                        new DeviceLockControllerScheduler(mContext,
                                mStateController).scheduleResumeProvisionAlarm();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        throw new RuntimeException("Failed to delay setup", t);
                    }
                }, sExecutor);
    }

    @Override
    public void scheduleKioskAppInstallation(LifecycleOwner owner,
            ProvisioningProgressController progressController, boolean isMandatory) {
        LogUtil.v(TAG, "Trigger setup flow");
        progressController.setProvisioningProgress(ProvisioningProgress.GETTING_DEVICE_READY);
        Futures.addCallback(SetupParametersClient.getInstance().getKioskPackage(),
                new FutureCallback<>() {

                    @Override
                    public void onSuccess(String kioskPackage) {
                        progressController.setProvisioningProgress(
                                ProvisioningProgress.INSTALLING_KIOSK_APP);
                        if (Build.isDebuggable()) {
                            try {
                                mContext.getPackageManager().getPackageInfo(
                                        kioskPackage,
                                        ApplicationInfo.FLAG_INSTALLED);
                                LogUtil.i(TAG, "Kiosk app is pre-installed");
                                progressController.setProvisioningProgress(
                                        ProvisioningProgress.OPENING_KIOSK_APP);
                                mStateController.postSetNextStateForEventRequest(PROVISION_KIOSK);
                            } catch (NameNotFoundException e) {
                                LogUtil.i(TAG, "Kiosk app is not pre-installed");
                                installFromPlay(kioskPackage);
                            }
                        } else {
                            installFromPlay(kioskPackage);
                        }
                    }

                    private void installFromPlay(String kioskPackage) {
                        Context applicationContext = mContext.getApplicationContext();
                        final Class<? extends ListenableWorker> playInstallTaskClass =
                                ((DeviceLockControllerApplication) applicationContext)
                                        .getPlayInstallPackageTaskClass();
                        if (playInstallTaskClass == null) {
                            onFailure(new Throwable("Play installation not supported!"));
                        }
                        LogUtil.v(TAG, "Installing kiosk app from play");

                        mContext.getMainExecutor().execute(() -> {
                            final OneTimeWorkRequest playInstallPackageTask =
                                    getPlayInstallPackageTask(playInstallTaskClass, kioskPackage);
                            WorkManager workManager = WorkManager.getInstance(mContext);
                            workManager.enqueueUniqueWork(
                                    SETUP_PLAY_INSTALL_TASKS_NAME,
                                    ExistingWorkPolicy.KEEP, playInstallPackageTask);
                            workManager.getWorkInfoByIdLiveData(playInstallPackageTask.getId())
                                    .observe(owner, workInfo -> {
                                        if (workInfo == null) return;

                                        WorkInfo.State state = workInfo.getState();
                                        LogUtil.d(TAG, "WorkInfo changed: " + workInfo);
                                        if (state == SUCCEEDED) {
                                            progressController.setProvisioningProgress(
                                                    ProvisioningProgress.OPENING_KIOSK_APP);
                                            mStateController.postSetNextStateForEventRequest(
                                                    PROVISION_KIOSK);
                                        } else if (state == FAILED) {
                                            onFailure(new RuntimeException(
                                                    "Play install failed!"));
                                        }
                                    });
                        });
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        LogUtil.w(TAG, "Failed to install kiosk app!", t);
                        if (isMandatory) {
                            progressController.setProvisioningProgress(
                                    ProvisioningProgress.PROVISION_FAILED_MANDATORY);
                            new DeviceLockControllerScheduler(
                                    mContext, mStateController).scheduleMandatoryResetDeviceAlarm();
                        } else {
                            progressController.setProvisioningProgress(
                                    ProvisioningProgress.PROVISIONING_FAILED);
                        }
                    }
                }, sExecutor);
    }

    @NonNull
    private static OneTimeWorkRequest getPlayInstallPackageTask(
            Class<? extends ListenableWorker> playInstallTaskClass, String kioskPackageName) {
        return new OneTimeWorkRequest.Builder(
                playInstallTaskClass).setInputData(
                new Data.Builder().putString(
                        EXTRA_KIOSK_PACKAGE, kioskPackageName).build()).build();
    }

    private void createNotification() {
        LogUtil.d(TAG, "createNotification");
        Context context = mContext;

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                /* requestCode= */ 0,
                new Intent(context, ResumeProvisionReceiver.class),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        LocalDateTime resumeDateTime = LocalDateTime.now().plusHours(1);
        DeviceLockNotificationManager.sendDeferredProvisioningNotification(context, resumeDateTime,
                pendingIntent);
    }
}
