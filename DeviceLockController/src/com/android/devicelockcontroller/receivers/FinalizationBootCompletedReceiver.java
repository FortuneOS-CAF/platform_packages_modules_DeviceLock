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

package com.android.devicelockcontroller.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

import com.android.devicelockcontroller.policy.PolicyObjectsProvider;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Boot complete receiver to initialize finalization state on device.
 */
public final class FinalizationBootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = FinalizationBootCompletedReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) return;

        final boolean isUserProfile =
                context.getSystemService(UserManager.class).isProfile();

        if (isUserProfile) {
            // Not needed as the receiver will run in the parent user
            return;
        }

        // Initialize finalization controller to apply device finalization state
        ListenableFuture<Void> enforced = ((PolicyObjectsProvider) context.getApplicationContext())
                .getFinalizationController().enforceDiskState(/* force= */ false);
        Futures.addCallback(enforced, new FutureCallback<>() {
            @Override
            public void onSuccess(Void result) {
                // no-op
            }

            @Override
            public void onFailure(Throwable t) {
                LogUtil.e(TAG, "Failed to enforce finalization state on boot!", t);
            }
        }, MoreExecutors.directExecutor());
    }
}
