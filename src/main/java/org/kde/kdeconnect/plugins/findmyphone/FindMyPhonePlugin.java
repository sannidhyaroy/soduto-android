/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <david@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.plugins.findmyphone;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.apache.commons.lang3.ArrayUtils;
import org.kde.kdeconnect.helpers.DeviceHelper;
import org.kde.kdeconnect.helpers.LifecycleHelper;
import org.kde.kdeconnect.helpers.NotificationHelper;
import org.kde.kdeconnect.NetworkPacket;
import org.kde.kdeconnect.plugins.Plugin;
import org.kde.kdeconnect.plugins.PluginFactory;
import org.kde.kdeconnect.ui.PluginSettingsFragment;
import org.kde.kdeconnect_tp.R;

import java.io.IOException;

@PluginFactory.LoadablePlugin
public class FindMyPhonePlugin extends Plugin {
    public final static String PACKET_TYPE_FINDMYPHONE_REQUEST = "kdeconnect.findmyphone.request";

    private NotificationManager notificationManager;
    private int notificationId;
    private AudioManager audioManager;
    private MediaPlayer mediaPlayer;
    private int previousVolume = -1;
    private PowerManager powerManager;
    private FlashlightManager flashlightManager;
    private SodutoRingTimeout ringTimeout;
    private SodutoRingStopper ringStopper;

    @Override
    public @NonNull String getDisplayName() {
        switch (DeviceHelper.getDeviceType()) {
            case TV:
                return context.getString(R.string.findmyphone_title_tv);
            case TABLET:
                return context.getString(R.string.findmyphone_title_tablet);
            case PHONE:
            default:
                return context.getString(R.string.findmyphone_title);
        }
    }

    @Override
    public @NonNull String getDescription() {
        return context.getString(R.string.findmyphone_description);
    }

    @Override
    public boolean onCreate() {
        notificationManager = ContextCompat.getSystemService(context, NotificationManager.class);
        notificationId = (int) System.currentTimeMillis();
        audioManager = ContextCompat.getSystemService(context, AudioManager.class);
        powerManager = ContextCompat.getSystemService(context, PowerManager.class);
        flashlightManager = new FlashlightManager(context);
        // Stop ringing by itself after a while so a lost device can't ring forever (Find My Device parity)
        ringTimeout = new SodutoRingTimeout(this::stopRinging);
        // Let a power-button press silence the ring like an incoming call
        ringStopper = new SodutoRingStopper(context, this::stopRinging);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Uri ringtone;
        String ringtoneString = prefs.getString(context.getString(R.string.findmyphone_preference_key_ringtone), "");
        if (ringtoneString.isEmpty()) {
            ringtone = Settings.System.DEFAULT_RINGTONE_URI;
        } else {
            ringtone = Uri.parse(ringtoneString);
        }

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(context, ringtone);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build();
            mediaPlayer.setWakeMode(context, PowerManager.SCREEN_DIM_WAKE_LOCK); // Prevent screen turning off, requires WAKE_LOCK permission
            mediaPlayer.setAudioAttributes(audioAttributes);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
        } catch (Exception e) {
            Log.e("FindMyPhoneActivity", "Exception", e);
            return false;
        }

        return true;
    }

    @Override
    public void onDestroy() {
        if (ringTimeout != null) {
            ringTimeout.cancel();
        }
        if (ringStopper != null) {
            ringStopper.stop();
        }
        if (mediaPlayer.isPlaying()) {
            stopPlaying();
        }
        audioManager = null;
        mediaPlayer.release();
        mediaPlayer = null;

        flashlightManager = null;
    }

    @Override
    public boolean onPacketReceived(@NonNull NetworkPacket np) {
        // Ring/flash immediately so the device can be located by sound; the notification
        // below is only there to let the user silence it again once found.
        startPlaying();
        startFlashing();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || LifecycleHelper.isInForeground()) {
            Intent intent = new Intent(context, FindMyPhoneActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(FindMyPhoneActivity.EXTRA_DEVICE_ID, getDevice().getDeviceId());
            context.startActivity(intent);
        } else if (powerManager.isInteractive()) {
            // Screen on: an ongoing "Found it" notification, like a call notification
            showBroadcastNotification();
        } else if (SodutoFindMyPhone.canUseFullScreenIntent(context)) {
            // Screen off, full-screen access granted: wake the screen with the ring activity, like a call
            showActivityNotification();
        }
        // Screen off without full-screen access: just ring. The power button or the auto-stop
        // timeout silences it — no lingering notification, the way Google Find My Device behaves.
        return true;
    }

    private PendingIntent foundItPendingIntent() {
        Intent intent = new Intent(context, FindMyPhoneReceiver.class);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.setAction(FindMyPhoneReceiver.ACTION_FOUND_IT);
        intent.putExtra(FindMyPhoneReceiver.EXTRA_DEVICE_ID, getDevice().getDeviceId());

        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void showBroadcastNotification() {
        // Screen on: tapping the notification body (or its action) silences the ring. No full-screen
        // intent here — the screen is already on, so it just shows as an ongoing heads-up.
        createNotification(foundItPendingIntent(), null);
    }

    private void showActivityNotification() {
        Intent intent = new Intent(context, FindMyPhoneActivity.class);
        intent.putExtra(FindMyPhoneActivity.EXTRA_DEVICE_ID, getDevice().getDeviceId());

        PendingIntent pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        createNotification(pi, pi);
    }

    private void createNotification(PendingIntent contentIntent, @Nullable PendingIntent fullScreenIntent) {
        // Persistent and call-like: stays put while ringing, with a "Found it" action to silence it.
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY);
        notification
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentTitle(context.getString(R.string.findmyphone_ringing_title))
                .addAction(R.drawable.ic_notification, context.getString(R.string.findmyphone_found), foundItPendingIntent());
        if (fullScreenIntent != null) {
            notification.setFullScreenIntent(fullScreenIntent, true);
        }
        notification.setGroup("BackgroundService");

        notificationManager.notify(notificationId, notification.build());
    }

    void startPlaying() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            // Make sure we are heard even when the phone is silent, restore original volume later
            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);

            mediaPlayer.start();
            ringTimeout.schedule();
            ringStopper.start();
        }
    }

    /** Single funnel for ending a ring from any trigger: the activity, the notification action,
     *  the power-button stopper or the auto-stop timeout. */
    void stopRinging() {
        stopPlaying();
        stopFlashing();
        hideNotification();
    }

    void startFlashing() {
        if (isFlashlightEnabledInSettings() && isPermissionGranted(Manifest.permission.CAMERA)) {
            flashlightManager.startFlashing();
        }
    }

    void hideNotification() {
        notificationManager.cancel(notificationId);
    }

    void stopPlaying() {
        if (ringTimeout != null) {
            ringTimeout.cancel();
        }
        if (ringStopper != null) {
            ringStopper.stop();
        }
        if (audioManager == null) {
            // The Plugin was destroyed (probably the device disconnected)
            return;
        }

        if (previousVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0);
        }
        mediaPlayer.stop();
        try {
            mediaPlayer.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void stopFlashing() {
        flashlightManager.stopFlashing();
    }

    boolean isPlaying() {
        return mediaPlayer.isPlaying();
    }

    @Override
    public @NonNull String[] getSupportedPacketTypes() {
        return new String[]{PACKET_TYPE_FINDMYPHONE_REQUEST};
    }

    @Override
    public @NonNull String[] getOutgoingPacketTypes() {
        return ArrayUtils.EMPTY_STRING_ARRAY;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public PluginSettingsFragment getSettingsFragment(Activity activity) {
        return FindMyPhoneSettingsFragment.newInstance(getPluginKey(), R.xml.findmyphoneplugin_preferences);
    }

    @NonNull
    @Override
    protected String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        } else {
            return ArrayUtils.EMPTY_STRING_ARRAY;
        }
    }

    @Override
    protected int getPermissionExplanation() {
        return R.string.findmyphone_notifications_explanation;
    }

    private boolean isFlashlightEnabledInSettings() {
       SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
       return prefs.getBoolean(context.getString(R.string.findmyphone_preference_key_flashlight), false);
    }
}
