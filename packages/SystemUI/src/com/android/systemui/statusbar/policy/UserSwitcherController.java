/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.util.UserIcons;
import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.BitmapHelper;
import com.android.systemui.GuestResumeSessionReceiver;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.tiles.UserDetailView;
import com.android.systemui.statusbar.phone.ActivityStarter;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * Keeps a list of all users on the device for user switching.
 */
public class UserSwitcherController {

    private static final String TAG = "UserSwitcherController";
    private static final boolean DEBUG = false;
    private static final String SIMPLE_USER_SWITCHER_GLOBAL_SETTING =
            "lockscreenSimpleUserSwitcher";
    private static final String ACTION_REMOVE_GUEST = "com.android.systemui.REMOVE_GUEST";
    private static final String ACTION_LOGOUT_USER = "com.android.systemui.LOGOUT_USER";
    private static final int PAUSE_REFRESH_USERS_TIMEOUT_MS = 3000;

    private static final int ID_REMOVE_GUEST = 1010;
    private static final int ID_LOGOUT_USER = 1011;
    private static final String TAG_REMOVE_GUEST = "remove_guest";
    private static final String TAG_LOGOUT_USER = "logout_user";

    private static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";

    private final Context mContext;
    private final UserManager mUserManager;
    private final ArrayList<WeakReference<BaseUserAdapter>> mAdapters = new ArrayList<>();
    private final GuestResumeSessionReceiver mGuestResumeSessionReceiver
            = new GuestResumeSessionReceiver();
    private final KeyguardMonitor mKeyguardMonitor;
    private final Handler mHandler;
    private final ActivityStarter mActivityStarter;

    private ArrayList<UserRecord> mUsers = new ArrayList<>();
    private Dialog mExitGuestDialog;
    private Dialog mAddUserDialog;
    private int mLastNonGuestUser = UserHandle.USER_SYSTEM;
    private boolean mSimpleUserSwitcher;
    private boolean mAddUsersWhenLocked;
    private boolean mPauseRefreshUsers;
    private SparseBooleanArray mForcePictureLoadForUserId = new SparseBooleanArray(2);

    public UserSwitcherController(Context context, KeyguardMonitor keyguardMonitor,
            Handler handler, ActivityStarter activityStarter) {
        mContext = context;
        mGuestResumeSessionReceiver.register(context);
        mKeyguardMonitor = keyguardMonitor;
        mHandler = handler;
        mActivityStarter = activityStarter;
        mUserManager = UserManager.get(context);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_STOPPING);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.SYSTEM, filter,
                null /* permission */, null /* scheduler */);

        filter = new IntentFilter();
        filter.addAction(ACTION_REMOVE_GUEST);
        filter.addAction(ACTION_LOGOUT_USER);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.SYSTEM, filter,
                PERMISSION_SELF, null /* scheduler */);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SIMPLE_USER_SWITCHER_GLOBAL_SETTING), true,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADD_USERS_WHEN_LOCKED), true,
                mSettingsObserver);
        // Fetch initial values.
        mSettingsObserver.onChange(false);

        keyguardMonitor.addCallback(mCallback);

        refreshUsers(UserHandle.USER_NULL);
    }

    /**
     * Refreshes users from UserManager.
     *
     * The pictures are only loaded if they have not been loaded yet.
     *
     * @param forcePictureLoadForId forces the picture of the given user to be reloaded.
     */
    @SuppressWarnings("unchecked")
    private void refreshUsers(int forcePictureLoadForId) {
        if (DEBUG) Log.d(TAG, "refreshUsers(forcePictureLoadForId=" + forcePictureLoadForId+")");
        if (forcePictureLoadForId != UserHandle.USER_NULL) {
            mForcePictureLoadForUserId.put(forcePictureLoadForId, true);
        }

        if (mPauseRefreshUsers) {
            return;
        }

        SparseArray<Bitmap> bitmaps = new SparseArray<>(mUsers.size());
        final int N = mUsers.size();
        for (int i = 0; i < N; i++) {
            UserRecord r = mUsers.get(i);
            if (r == null || r.picture == null ||
                    r.info == null || mForcePictureLoadForUserId.get(r.info.id)) {
                continue;
            }
            bitmaps.put(r.info.id, r.picture);
        }
        mForcePictureLoadForUserId.clear();

        final boolean addUsersWhenLocked = mAddUsersWhenLocked;
        new AsyncTask<SparseArray<Bitmap>, Void, ArrayList<UserRecord>>() {
            @SuppressWarnings("unchecked")
            @Override
            protected ArrayList<UserRecord> doInBackground(SparseArray<Bitmap>... params) {
                final SparseArray<Bitmap> bitmaps = params[0];
                List<UserInfo> infos = mUserManager.getUsers(true);
                if (infos == null) {
                    return null;
                }
                ArrayList<UserRecord> records = new ArrayList<>(infos.size());
                int currentId = ActivityManager.getCurrentUser();
                UserInfo currentUserInfo = null;
                UserRecord guestRecord = null;
                int avatarSize = mContext.getResources()
                        .getDimensionPixelSize(R.dimen.max_avatar_size);

                for (UserInfo info : infos) {
                    boolean isCurrent = currentId == info.id;
                    if (isCurrent) {
                        currentUserInfo = info;
                    }
                    if (info.isGuest()) {
                        guestRecord = new UserRecord(info, null /* picture */,
                                true /* isGuest */, isCurrent, false /* isAddUser */,
                                false /* isRestricted */);
                    } else if (info.isEnabled() && info.supportsSwitchToByUser()) {
                        Bitmap picture = bitmaps.get(info.id);
                        if (picture == null) {
                            picture = mUserManager.getUserIcon(info.id);

                            if (picture != null) {
                                picture = BitmapHelper.createCircularClip(
                                        picture, avatarSize, avatarSize);
                            }
                        }
                        int index = isCurrent ? 0 : records.size();
                        records.add(index, new UserRecord(info, picture, false /* isGuest */,
                                isCurrent, false /* isAddUser */, false /* isRestricted */));
                    }
                }

                boolean currentUserCanCreateUsers = currentUserInfo != null
                        && (currentUserInfo.isAdmin()
                                || currentUserInfo.id == UserHandle.USER_SYSTEM);
                boolean canCreateGuest = (currentUserCanCreateUsers || addUsersWhenLocked)
                        && guestRecord == null;
                boolean canCreateUser = (currentUserCanCreateUsers || addUsersWhenLocked)
                        && mUserManager.canAddMoreUsers();
                boolean createIsRestricted = !addUsersWhenLocked;

                if (!mSimpleUserSwitcher) {
                    if (guestRecord == null) {
                        if (canCreateGuest) {
                            guestRecord = new UserRecord(null /* info */, null /* picture */,
                                    true /* isGuest */, false /* isCurrent */,
                                    false /* isAddUser */, createIsRestricted);
                            checkIfAddUserDisallowed(guestRecord);
                            records.add(guestRecord);
                        }
                    } else {
                        int index = guestRecord.isCurrent ? 0 : records.size();
                        records.add(index, guestRecord);
                    }
                }

                if (!mSimpleUserSwitcher && canCreateUser) {
                    UserRecord addUserRecord = new UserRecord(null /* info */, null /* picture */,
                            false /* isGuest */, false /* isCurrent */, true /* isAddUser */,
                            createIsRestricted);
                    checkIfAddUserDisallowed(addUserRecord);
                    records.add(addUserRecord);
                }

                return records;
            }

            @Override
            protected void onPostExecute(ArrayList<UserRecord> userRecords) {
                if (userRecords != null) {
                    mUsers = userRecords;
                    notifyAdapters();
                }
            }
        }.execute((SparseArray) bitmaps);
    }

    private void pauseRefreshUsers() {
        if (!mPauseRefreshUsers) {
            mHandler.postDelayed(mUnpauseRefreshUsers, PAUSE_REFRESH_USERS_TIMEOUT_MS);
            mPauseRefreshUsers = true;
        }
    }

    private void notifyAdapters() {
        for (int i = mAdapters.size() - 1; i >= 0; i--) {
            BaseUserAdapter adapter = mAdapters.get(i).get();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            } else {
                mAdapters.remove(i);
            }
        }
    }

    public boolean isSimpleUserSwitcher() {
        return mSimpleUserSwitcher;
    }

    public boolean useFullscreenUserSwitcher() {
        // Use adb to override:
        // adb shell settings put system enable_fullscreen_user_switcher 0  # Turn it off.
        // adb shell settings put system enable_fullscreen_user_switcher 1  # Turn it on.
        // Restart SystemUI or adb reboot.
        final int DEFAULT = -1;
        final int overrideUseFullscreenUserSwitcher =
                Settings.System.getInt(mContext.getContentResolver(),
                        "enable_fullscreen_user_switcher", DEFAULT);
        if (overrideUseFullscreenUserSwitcher != DEFAULT) {
            return overrideUseFullscreenUserSwitcher != 0;
        }
        // Otherwise default to the build setting.
        return mContext.getResources().getBoolean(R.bool.config_enableFullscreenUserSwitcher);
    }

    public void logoutCurrentUser() {
        int currentUser = ActivityManager.getCurrentUser();
        if (currentUser != UserHandle.USER_SYSTEM) {
            switchToUserId(UserHandle.USER_SYSTEM);
            stopUserId(currentUser);
        }
    }

    public void removeUserId(int userId) {
        if (userId == UserHandle.USER_SYSTEM) {
            Log.w(TAG, "User " + userId + " could not removed.");
            return;
        }
        if (ActivityManager.getCurrentUser() == userId) {
            switchToUserId(UserHandle.USER_SYSTEM);
        }
        if (mUserManager.removeUser(userId)) {
            refreshUsers(UserHandle.USER_NULL);
        }
    }

    public void switchTo(UserRecord record) {
        int id;
        if (record.isGuest && record.info == null) {
            // No guest user. Create one.
            UserInfo guest = mUserManager.createGuest(
                    mContext, mContext.getString(R.string.guest_nickname));
            if (guest == null) {
                // Couldn't create guest, most likely because there already exists one, we just
                // haven't reloaded the user list yet.
                return;
            }
            id = guest.id;
        } else if (record.isAddUser) {
            showAddUserDialog();
            return;
        } else {
            id = record.info.id;
        }

        if (ActivityManager.getCurrentUser() == id) {
            if (record.isGuest) {
                showExitGuestDialog(id);
            }
            return;
        }

        switchToUserId(id);
    }

    public void switchTo(int userId) {
        final int count = mUsers.size();
        for (int i = 0; i < count; ++i) {
            UserRecord record = mUsers.get(i);
            if (record.info != null && record.info.id == userId) {
                switchTo(record);
                return;
            }
        }

        Log.e(TAG, "Couldn't switch to user, id=" + userId);
    }

    private void switchToUserId(int id) {
        try {
            pauseRefreshUsers();
            ActivityManagerNative.getDefault().switchUser(id);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    private void stopUserId(int id) {
        try {
            ActivityManagerNative.getDefault().stopUser(id, /* force= */ false, null);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't stop user.", e);
        }
    }

    private void showExitGuestDialog(int id) {
        if (mExitGuestDialog != null && mExitGuestDialog.isShowing()) {
            mExitGuestDialog.cancel();
        }
        mExitGuestDialog = new ExitGuestDialog(mContext, id);
        mExitGuestDialog.show();
    }

    private void showAddUserDialog() {
        if (mAddUserDialog != null && mAddUserDialog.isShowing()) {
            mAddUserDialog.cancel();
        }
        mAddUserDialog = new AddUserDialog(mContext);
        mAddUserDialog.show();
    }

    private void exitGuest(int id) {
        int newId = UserHandle.USER_SYSTEM;
        if (mLastNonGuestUser != UserHandle.USER_SYSTEM) {
            UserInfo info = mUserManager.getUserInfo(mLastNonGuestUser);
            if (info != null && info.isEnabled() && info.supportsSwitchToByUser()) {
                newId = info.id;
            }
        }
        switchToUserId(newId);
        mUserManager.removeUser(id);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.v(TAG, "Broadcast: a=" + intent.getAction()
                       + " user=" + intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1));
            }

            boolean unpauseRefreshUsers = false;
            int forcePictureLoadForId = UserHandle.USER_NULL;

            if (ACTION_REMOVE_GUEST.equals(intent.getAction())) {
                int currentUser = ActivityManager.getCurrentUser();
                UserInfo userInfo = mUserManager.getUserInfo(currentUser);
                if (userInfo != null && userInfo.isGuest()) {
                    showExitGuestDialog(currentUser);
                }
                return;
            } else if (ACTION_LOGOUT_USER.equals(intent.getAction())) {
                logoutCurrentUser();
            } else if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                if (mExitGuestDialog != null && mExitGuestDialog.isShowing()) {
                    mExitGuestDialog.cancel();
                    mExitGuestDialog = null;
                }

                final int currentId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                final UserInfo userInfo = mUserManager.getUserInfo(currentId);
                final int N = mUsers.size();
                for (int i = 0; i < N; i++) {
                    UserRecord record = mUsers.get(i);
                    if (record.info == null) continue;
                    boolean shouldBeCurrent = record.info.id == currentId;
                    if (record.isCurrent != shouldBeCurrent) {
                        mUsers.set(i, record.copyWithIsCurrent(shouldBeCurrent));
                    }
                    if (shouldBeCurrent && !record.isGuest) {
                        mLastNonGuestUser = record.info.id;
                    }
                    if ((userInfo == null || !userInfo.isAdmin()) && record.isRestricted) {
                        // Immediately remove restricted records in case the AsyncTask is too slow.
                        mUsers.remove(i);
                        i--;
                    }
                }
                notifyAdapters();

                if (UserManager.isSplitSystemUser() && userInfo != null && !userInfo.isGuest()
                        && userInfo.id != UserHandle.USER_SYSTEM) {
                    showLogoutNotification(currentId);
                }
                if (userInfo != null && userInfo.isGuest()) {
                    showGuestNotification(currentId);
                }
                unpauseRefreshUsers = true;
            } else if (Intent.ACTION_USER_INFO_CHANGED.equals(intent.getAction())) {
                forcePictureLoadForId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
            }
            refreshUsers(forcePictureLoadForId);
            if (unpauseRefreshUsers) {
                mUnpauseRefreshUsers.run();
            }
        }

        private void showGuestNotification(int guestUserId) {
            PendingIntent removeGuestPI = PendingIntent.getBroadcastAsUser(mContext,
                    0, new Intent(ACTION_REMOVE_GUEST), 0, UserHandle.SYSTEM);
            Notification notification = new Notification.Builder(mContext)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setPriority(Notification.PRIORITY_MIN)
                    .setSmallIcon(R.drawable.ic_person)
                    .setContentTitle(mContext.getString(R.string.guest_notification_title))
                    .setContentText(mContext.getString(R.string.guest_notification_text))
                    .setContentIntent(removeGuestPI)
                    .setShowWhen(false)
                    .addAction(R.drawable.ic_delete,
                            mContext.getString(R.string.guest_notification_remove_action),
                            removeGuestPI)
                    .build();
            NotificationManager.from(mContext).notifyAsUser(TAG_REMOVE_GUEST, ID_REMOVE_GUEST,
                    notification, new UserHandle(guestUserId));
        }

        private void showLogoutNotification(int userId) {
            PendingIntent logoutPI = PendingIntent.getBroadcastAsUser(mContext,
                    0, new Intent(ACTION_LOGOUT_USER), 0, UserHandle.SYSTEM);
            Notification notification = new Notification.Builder(mContext)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setPriority(Notification.PRIORITY_MIN)
                    .setSmallIcon(R.drawable.ic_person)
                    .setContentTitle(mContext.getString(R.string.user_logout_notification_title))
                    .setContentText(mContext.getString(R.string.user_logout_notification_text))
                    .setContentIntent(logoutPI)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .addAction(R.drawable.ic_delete,
                            mContext.getString(R.string.user_logout_notification_action),
                            logoutPI)
                    .build();
            NotificationManager.from(mContext).notifyAsUser(TAG_LOGOUT_USER, ID_LOGOUT_USER,
                    notification, new UserHandle(userId));
        }
    };

    private final Runnable mUnpauseRefreshUsers = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(this);
            mPauseRefreshUsers = false;
            refreshUsers(UserHandle.USER_NULL);
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            mSimpleUserSwitcher = Settings.Global.getInt(mContext.getContentResolver(),
                    SIMPLE_USER_SWITCHER_GLOBAL_SETTING, 0) != 0;
            mAddUsersWhenLocked = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.ADD_USERS_WHEN_LOCKED, 0) != 0;
            refreshUsers(UserHandle.USER_NULL);
        };
    };

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UserSwitcherController state:");
        pw.println("  mLastNonGuestUser=" + mLastNonGuestUser);
        pw.print("  mUsers.size="); pw.println(mUsers.size());
        for (int i = 0; i < mUsers.size(); i++) {
            final UserRecord u = mUsers.get(i);
            pw.print("    "); pw.println(u.toString());
        }
    }

    public String getCurrentUserName(Context context) {
        if (mUsers.isEmpty()) return null;
        UserRecord item = mUsers.get(0);
        if (item == null || item.info == null) return null;
        if (item.isGuest) return context.getString(R.string.guest_nickname);
        return item.info.name;
    }

    public static abstract class BaseUserAdapter extends BaseAdapter {

        final UserSwitcherController mController;

        protected BaseUserAdapter(UserSwitcherController controller) {
            mController = controller;
            controller.mAdapters.add(new WeakReference<>(this));
        }

        @Override
        public int getCount() {
            boolean secureKeyguardShowing = mController.mKeyguardMonitor.isShowing()
                    && mController.mKeyguardMonitor.isSecure()
                    && !mController.mKeyguardMonitor.canSkipBouncer();
            if (!secureKeyguardShowing) {
                return mController.mUsers.size();
            }
            // The lock screen is secure and showing. Filter out restricted records.
            final int N = mController.mUsers.size();
            int count = 0;
            for (int i = 0; i < N; i++) {
                if (mController.mUsers.get(i).isRestricted) {
                    break;
                } else {
                    count++;
                }
            }
            return count;
        }

        @Override
        public UserRecord getItem(int position) {
            return mController.mUsers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        public void switchTo(UserRecord record) {
            mController.switchTo(record);
        }

        public String getName(Context context, UserRecord item) {
            if (item.isGuest) {
                if (item.isCurrent) {
                    return context.getString(R.string.guest_exit_guest);
                } else {
                    return context.getString(
                            item.info == null ? R.string.guest_new_guest : R.string.guest_nickname);
                }
            } else if (item.isAddUser) {
                return context.getString(R.string.user_add_user);
            } else {
                return item.info.name;
            }
        }

        public Drawable getDrawable(Context context, UserRecord item) {
            if (item.isAddUser) {
                return context.getDrawable(R.drawable.ic_add_circle_qs);
            }
            return UserIcons.getDefaultUserIcon(item.isGuest ? UserHandle.USER_NULL : item.info.id,
                    /* light= */ true);
        }

        public void refresh() {
            mController.refreshUsers(UserHandle.USER_NULL);
        }
    }

    private void checkIfAddUserDisallowed(UserRecord record) {
        EnforcedAdmin admin = RestrictedLockUtils.checkIfRestrictionEnforced(mContext,
                UserManager.DISALLOW_ADD_USER, UserHandle.myUserId());
        if (admin != null) {
            record.isDisabledByAdmin = true;
            record.enforcedAdmin = admin;
        } else {
            record.isDisabledByAdmin = false;
            record.enforcedAdmin = null;
        }
    }

    public void startActivity(Intent intent) {
        mActivityStarter.startActivity(intent, true);
    }

    public static final class UserRecord {
        public final UserInfo info;
        public final Bitmap picture;
        public final boolean isGuest;
        public final boolean isCurrent;
        public final boolean isAddUser;
        /** If true, the record is only visible to the owner and only when unlocked. */
        public final boolean isRestricted;
        public boolean isDisabledByAdmin;
        public EnforcedAdmin enforcedAdmin;

        public UserRecord(UserInfo info, Bitmap picture, boolean isGuest, boolean isCurrent,
                boolean isAddUser, boolean isRestricted) {
            this.info = info;
            this.picture = picture;
            this.isGuest = isGuest;
            this.isCurrent = isCurrent;
            this.isAddUser = isAddUser;
            this.isRestricted = isRestricted;
        }

        public UserRecord copyWithIsCurrent(boolean _isCurrent) {
            return new UserRecord(info, picture, isGuest, _isCurrent, isAddUser, isRestricted);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UserRecord(");
            if (info != null) {
                sb.append("name=\"" + info.name + "\" id=" + info.id);
            } else {
                if (isGuest) {
                    sb.append("<add guest placeholder>");
                } else if (isAddUser) {
                    sb.append("<add user placeholder>");
                }
            }
            if (isGuest) sb.append(" <isGuest>");
            if (isAddUser) sb.append(" <isAddUser>");
            if (isCurrent) sb.append(" <isCurrent>");
            if (picture != null) sb.append(" <hasPicture>");
            if (isRestricted) sb.append(" <isRestricted>");
            if (isDisabledByAdmin) {
                sb.append(" <isDisabledByAdmin>");
                sb.append(" enforcedAdmin=" + enforcedAdmin);
            }
            sb.append(')');
            return sb.toString();
        }
    }

    public final QSTile.DetailAdapter userDetailAdapter = new QSTile.DetailAdapter() {
        private final Intent USER_SETTINGS_INTENT = new Intent("android.settings.USER_SETTINGS");

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_user_title);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            UserDetailView v;
            if (!(convertView instanceof UserDetailView)) {
                v = UserDetailView.inflate(context, parent, false);
                v.createAndSetAdapter(UserSwitcherController.this);
            } else {
                v = (UserDetailView) convertView;
            }
            v.refreshAdapter();
            return v;
        }

        @Override
        public Intent getSettingsIntent() {
            return USER_SETTINGS_INTENT;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public void setToggleState(boolean state) {
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QS_USERDETAIL;
        }
    };

    private final KeyguardMonitor.Callback mCallback = new KeyguardMonitor.Callback() {
        @Override
        public void onKeyguardChanged() {
            notifyAdapters();
        }
    };

    private final class ExitGuestDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        private final int mGuestId;

        public ExitGuestDialog(Context context, int guestId) {
            super(context);
            setTitle(R.string.guest_exit_guest_dialog_title);
            setMessage(context.getString(R.string.guest_exit_guest_dialog_message));
            setButton(DialogInterface.BUTTON_NEGATIVE,
                    context.getString(android.R.string.cancel), this);
            setButton(DialogInterface.BUTTON_POSITIVE,
                    context.getString(R.string.guest_exit_guest_dialog_remove), this);
            setCanceledOnTouchOutside(false);
            mGuestId = guestId;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == BUTTON_NEGATIVE) {
                cancel();
            } else {
                dismiss();
                exitGuest(mGuestId);
            }
        }
    }

    private final class AddUserDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        public AddUserDialog(Context context) {
            super(context);
            setTitle(R.string.user_add_user_title);
            setMessage(context.getString(R.string.user_add_user_message_short));
            setButton(DialogInterface.BUTTON_NEGATIVE,
                    context.getString(android.R.string.cancel), this);
            setButton(DialogInterface.BUTTON_POSITIVE,
                    context.getString(android.R.string.ok), this);
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == BUTTON_NEGATIVE) {
                cancel();
            } else {
                dismiss();
                if (ActivityManager.isUserAMonkey()) {
                    return;
                }
                UserInfo user = mUserManager.createUser(
                        mContext.getString(R.string.user_new_user_name), 0 /* flags */);
                if (user == null) {
                    // Couldn't create user, most likely because there are too many, but we haven't
                    // been able to reload the list yet.
                    return;
                }
                int id = user.id;
                Bitmap icon = UserIcons.convertToBitmap(UserIcons.getDefaultUserIcon(
                        id, /* light= */ false));
                mUserManager.setUserIcon(id, icon);
                switchToUserId(id);
            }
        }
    }
}
