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

package com.android.devicelockcontroller.activities;

import android.annotation.NonNull;
import android.app.Application;

import com.android.devicelockcontroller.R;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides resources and data used to be displayed for secondary users for the device
 * financing use case.
 */
public final class DeviceFinancingSecondaryUserProvisionInfoViewModel extends
        ProvisionInfoViewModel {

    private static final int HEADER_DRAWABLE_ID = R.drawable.ic_info_24px;

    private static final int HEADER_TEXT_ID = R.string.device_provided_by_provider;

    private static final Integer[] DRAWABLE_IDS = new Integer[]{
            R.drawable.ic_file_download_24px, R.drawable.ic_lock_outline_24px,
    };

    private static final Integer[] TEXT_IDS = new Integer[]{
            R.string.install_kiosk_app_secondary_user,
            R.string.restrict_device_if_owner_doesnt_make_payment,
    };

    public DeviceFinancingSecondaryUserProvisionInfoViewModel(@NonNull Application application) {
        super(application);

        mHeaderDrawableId = HEADER_DRAWABLE_ID;
        mHeaderTextId = HEADER_TEXT_ID;

        List<ProvisionInfo> provisionInfoList = new ArrayList<>();
        for (int i = 0, size = DRAWABLE_IDS.length; i < size; ++i) {
            provisionInfoList.add(new ProvisionInfo(DRAWABLE_IDS[i], TEXT_IDS[i]));
        }
        mProvisionInfoList = provisionInfoList;
    }
}
