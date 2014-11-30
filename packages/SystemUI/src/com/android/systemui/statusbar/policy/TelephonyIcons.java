/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.MobileSignalController.MobileIconGroup;

import java.util.HashMap;
import java.util.Map;

class TelephonyIcons {
    //***** Data connection icons
<<<<<<< HEAD   (77f580 Merge tag 'android-10.0.0_r5' into ten)
=======

    static final int QS_DATA_G = R.drawable.ic_qs_signal_g;
    static final int QS_DATA_3G = R.drawable.ic_qs_signal_3g;
    static final int QS_DATA_E = R.drawable.ic_qs_signal_e;
    static final int QS_DATA_H = R.drawable.ic_qs_signal_h;
    static final int QS_DATA_HP = R.drawable.ic_qs_signal_hp;
    static final int QS_DATA_1X = R.drawable.ic_qs_signal_1x;
    static final int QS_DATA_4G = R.drawable.ic_qs_signal_4g;
    static final int QS_DATA_4G_PLUS = R.drawable.ic_qs_signal_4g_plus;
    static final int QS_DATA_LTE = R.drawable.ic_qs_signal_lte;
    static final int QS_DATA_LTE_PLUS = R.drawable.ic_qs_signal_lte_plus;

>>>>>>> CHANGE (b1f2fd Status bar: Add HSPA+ icons)
    static final int FLIGHT_MODE_ICON = R.drawable.stat_sys_airplane_mode;

<<<<<<< HEAD   (77f580 Merge tag 'android-10.0.0_r5' into ten)
    static final int ICON_LTE = R.drawable.ic_lte_mobiledata;
    static final int ICON_LTE_PLUS = R.drawable.ic_lte_plus_mobiledata;
    static final int ICON_G = R.drawable.ic_g_mobiledata;
    static final int ICON_E = R.drawable.ic_e_mobiledata;
    static final int ICON_H = R.drawable.ic_h_mobiledata;
    static final int ICON_H_PLUS = R.drawable.ic_h_plus_mobiledata;
    static final int ICON_3G = R.drawable.ic_3g_mobiledata;
    static final int ICON_4G = R.drawable.ic_4g_mobiledata;
    static final int ICON_4G_PLUS = R.drawable.ic_4g_plus_mobiledata;
    static final int ICON_5G_E = R.drawable.ic_5g_e_mobiledata;
    static final int ICON_1X = R.drawable.ic_1x_mobiledata;
    static final int ICON_5G = R.drawable.ic_5g_mobiledata;
    static final int ICON_5G_PLUS = R.drawable.ic_5g_plus_mobiledata;
=======
    static final int ICON_LTE = R.drawable.stat_sys_data_fully_connected_lte;
    static final int ICON_LTE_PLUS = R.drawable.stat_sys_data_fully_connected_lte_plus;
    static final int ICON_G = R.drawable.stat_sys_data_fully_connected_g;
    static final int ICON_E = R.drawable.stat_sys_data_fully_connected_e;
    static final int ICON_H = R.drawable.stat_sys_data_fully_connected_h;
    static final int ICON_HP = R.drawable.stat_sys_data_fully_connected_hp;
    static final int ICON_3G = R.drawable.stat_sys_data_fully_connected_3g;
    static final int ICON_4G = R.drawable.stat_sys_data_fully_connected_4g;
    static final int ICON_4G_PLUS = R.drawable.stat_sys_data_fully_connected_4g_plus;
    static final int ICON_1X = R.drawable.stat_sys_data_fully_connected_1x;

    static final int ICON_DATA_DISABLED = R.drawable.stat_sys_data_disabled;

    static final int QS_ICON_DATA_DISABLED = R.drawable.ic_qs_data_disabled;
>>>>>>> CHANGE (b1f2fd Status bar: Add HSPA+ icons)

    static final MobileIconGroup CARRIER_NETWORK_CHANGE = new MobileIconGroup(
            "CARRIER_NETWORK_CHANGE",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.carrier_network_change_mode,
            0,
            false);

    static final MobileIconGroup THREE_G = new MobileIconGroup(
            "3G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_3g,
            TelephonyIcons.ICON_3G,
            true);

    static final MobileIconGroup WFC = new MobileIconGroup(
            "WFC",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            0, 0, false);

    static final MobileIconGroup UNKNOWN = new MobileIconGroup(
            "Unknown",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            0, 0, false);

    static final MobileIconGroup E = new MobileIconGroup(
            "E",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_edge,
            TelephonyIcons.ICON_E,
            false);

    static final MobileIconGroup ONE_X = new MobileIconGroup(
            "1X",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_cdma,
            TelephonyIcons.ICON_1X,
            true);

    static final MobileIconGroup G = new MobileIconGroup(
            "G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_gprs,
            TelephonyIcons.ICON_G,
            false);

    static final MobileIconGroup H = new MobileIconGroup(
            "H",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
<<<<<<< HEAD   (77f580 Merge tag 'android-10.0.0_r5' into ten)
            R.string.data_connection_3_5g,
=======
            R.string.accessibility_data_connection_hspa,
>>>>>>> CHANGE (b1f2fd Status bar: Add HSPA+ icons)
            TelephonyIcons.ICON_H,
            false);

    static final MobileIconGroup H_PLUS = new MobileIconGroup(
            "H+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_3_5g_plus,
            TelephonyIcons.ICON_H_PLUS,
            false);

    static final MobileIconGroup HP = new MobileIconGroup(
            "HP",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.accessibility_data_connection_hspap,
            TelephonyIcons.ICON_HP,
            false,
            TelephonyIcons.QS_DATA_HP
            );

    static final MobileIconGroup FOUR_G = new MobileIconGroup(
            "4G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_4g,
            TelephonyIcons.ICON_4G,
            true);

    static final MobileIconGroup FOUR_G_PLUS = new MobileIconGroup(
            "4G+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0,0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_4g_plus,
            TelephonyIcons.ICON_4G_PLUS,
            true);

    static final MobileIconGroup LTE = new MobileIconGroup(
            "LTE",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_lte,
            TelephonyIcons.ICON_LTE,
            true);

    static final MobileIconGroup LTE_PLUS = new MobileIconGroup(
            "LTE+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_lte_plus,
            TelephonyIcons.ICON_LTE_PLUS,
            true);

    static final MobileIconGroup LTE_CA_5G_E = new MobileIconGroup(
            "5Ge",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_5ge,
            TelephonyIcons.ICON_5G_E,
            true);

    static final MobileIconGroup NR_5G = new MobileIconGroup(
            "5G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0,
            0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_5g,
            TelephonyIcons.ICON_5G,
            true);

    static final MobileIconGroup NR_5G_PLUS = new MobileIconGroup(
            "5G_PLUS",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0,
            0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_5g_plus,
            TelephonyIcons.ICON_5G_PLUS,
            true);

    static final MobileIconGroup DATA_DISABLED = new MobileIconGroup(
            "DataDisabled",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.cell_data_off_content_description,
            0,
            false);

    static final MobileIconGroup NOT_DEFAULT_DATA = new MobileIconGroup(
            "NotDefaultData",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.not_default_data_content_description,
            0,
            false);

    // When adding a new MobileIconGround, check if the dataContentDescription has to be filtered
    // in QSCarrier#hasValidTypeContentDescription

    /** Mapping icon name(lower case) to the icon object. */
    static final Map<String, MobileIconGroup> ICON_NAME_TO_ICON;
    static {
        ICON_NAME_TO_ICON = new HashMap<>();
        ICON_NAME_TO_ICON.put("carrier_network_change", CARRIER_NETWORK_CHANGE);
        ICON_NAME_TO_ICON.put("3g", THREE_G);
        ICON_NAME_TO_ICON.put("wfc", WFC);
        ICON_NAME_TO_ICON.put("unknown", UNKNOWN);
        ICON_NAME_TO_ICON.put("e", E);
        ICON_NAME_TO_ICON.put("1x", ONE_X);
        ICON_NAME_TO_ICON.put("g", G);
        ICON_NAME_TO_ICON.put("h", H);
        ICON_NAME_TO_ICON.put("h+", H_PLUS);
        ICON_NAME_TO_ICON.put("4g", FOUR_G);
        ICON_NAME_TO_ICON.put("4g+", FOUR_G_PLUS);
        ICON_NAME_TO_ICON.put("5ge", LTE_CA_5G_E);
        ICON_NAME_TO_ICON.put("lte", LTE);
        ICON_NAME_TO_ICON.put("lte+", LTE_PLUS);
        ICON_NAME_TO_ICON.put("5g", NR_5G);
        ICON_NAME_TO_ICON.put("5g_plus", NR_5G_PLUS);
        ICON_NAME_TO_ICON.put("datadisable", DATA_DISABLED);
        ICON_NAME_TO_ICON.put("notdefaultdata", NOT_DEFAULT_DATA);
    }
}

