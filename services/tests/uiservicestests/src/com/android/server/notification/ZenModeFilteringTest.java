/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distriZenbuted on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.app.Notification.CATEGORY_CALL;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_NONE;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CALLS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_ANY;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.provider.Settings.Global.ZEN_MODE_ALARMS;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_OFF;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager.Policy;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ZenModeFilteringTest extends UiServiceTestCase {

    @Mock
    private NotificationMessagingUtil mMessagingUtil;
    private ZenModeFiltering mZenModeFiltering;

    @Mock private TelephonyManager mTelephonyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mZenModeFiltering = new ZenModeFiltering(mContext, mMessagingUtil);

        // for repeat callers / matchesCallFilter
        mContext.addMockSystemService(TelephonyManager.class, mTelephonyManager);
    }

    private NotificationRecord getNotificationRecord() {
        return getNotificationRecord(mock(NotificationChannel.class));
    }

    private NotificationRecord getNotificationRecord(NotificationChannel c) {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        Notification notification = mock(Notification.class);
        when(sbn.getNotification()).thenReturn(notification);
        return new NotificationRecord(mContext, sbn, c);
    }

    private NotificationRecord getConversationRecord(NotificationChannel c,
            StatusBarNotification sbn) {
        NotificationRecord r = mock(NotificationRecord.class);
        when(r.getCriticality()).thenReturn(CriticalNotificationExtractor.NORMAL);
        when(r.getSbn()).thenReturn(sbn);
        when(r.getChannel()).thenReturn(c);
        when(r.isConversation()).thenReturn(true);
        return r;
    }

    private Bundle makeExtrasBundleWithPeople(String[] people) {
        Bundle extras = new Bundle();
        extras.putObject(Notification.EXTRA_PEOPLE_LIST, people);
        return extras;
    }

    private NotificationRecord getNotificationRecordWithPeople(String[] people) {
        // set up notification record
        NotificationRecord r = mock(NotificationRecord.class);
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        Notification notification = mock(Notification.class);
        notification.extras = makeExtrasBundleWithPeople(people);
        when(sbn.getNotification()).thenReturn(notification);
        when(r.getSbn()).thenReturn(sbn);
        return r;
    }

    @Test
    public void testIsMessage() {
        NotificationRecord r = getNotificationRecord();

        when(mMessagingUtil.isMessaging(any())).thenReturn(true);
        assertTrue(mZenModeFiltering.isMessage(r));

        when(mMessagingUtil.isMessaging(any())).thenReturn(false);
        assertFalse(mZenModeFiltering.isMessage(r));
    }

    @Test
    public void testIsAlarm() {
        NotificationChannel c = mock(NotificationChannel.class);
        when(c.getAudioAttributes()).thenReturn(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build());
        NotificationRecord r = getNotificationRecord(c);
        assertTrue(mZenModeFiltering.isAlarm(r));

        r = getNotificationRecord();
        r.getSbn().getNotification().category = Notification.CATEGORY_ALARM;
        assertTrue(mZenModeFiltering.isAlarm(r));
    }

    @Test
    public void testIsAlarm_wrongCategory() {
        NotificationRecord r = getNotificationRecord();
        r.getSbn().getNotification().category = CATEGORY_CALL;
        assertFalse(mZenModeFiltering.isAlarm(r));
    }

    @Test
    public void testIsAlarm_wrongUsage() {
        NotificationChannel c = mock(NotificationChannel.class);
        when(c.getAudioAttributes()).thenReturn(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build());
        NotificationRecord r = getNotificationRecord(c);
        assertFalse(mZenModeFiltering.isAlarm(r));
    }

    @Test
    public void testSuppressDNDInfo_yes_VisEffectsAllowed() {
        NotificationRecord r = getNotificationRecord();
        when(r.getSbn().getPackageName()).thenReturn("android");
        when(r.getSbn().getId()).thenReturn(SystemMessage.NOTE_ZEN_UPGRADE);
        Policy policy = new Policy(0, 0, 0, Policy.getAllSuppressedVisualEffects()
                - SUPPRESSED_EFFECT_STATUS_BAR, 0);

        assertTrue(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testSuppressDNDInfo_yes_WrongId() {
        NotificationRecord r = getNotificationRecord();
        when(r.getSbn().getPackageName()).thenReturn("android");
        when(r.getSbn().getId()).thenReturn(SystemMessage.NOTE_ACCOUNT_CREDENTIAL_PERMISSION);
        Policy policy = new Policy(0, 0, 0, Policy.getAllSuppressedVisualEffects(), 0);

        assertTrue(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testSuppressDNDInfo_yes_WrongPackage() {
        NotificationRecord r = getNotificationRecord();
        when(r.getSbn().getPackageName()).thenReturn("android2");
        when(r.getSbn().getId()).thenReturn(SystemMessage.NOTE_ZEN_UPGRADE);
        Policy policy = new Policy(0, 0, 0, Policy.getAllSuppressedVisualEffects(), 0);

        assertTrue(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testSuppressDNDInfo_no() {
        NotificationRecord r = getNotificationRecord();
        when(r.getSbn().getPackageName()).thenReturn("android");
        when(r.getSbn().getId()).thenReturn(SystemMessage.NOTE_ZEN_UPGRADE);
        Policy policy = new Policy(0, 0, 0, Policy.getAllSuppressedVisualEffects(), 0);

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_ALARMS, policy, r));
        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_NO_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testSuppressAnything_yes_ZenModeOff() {
        NotificationRecord r = getNotificationRecord();
        when(r.getSbn().getPackageName()).thenReturn("bananas");
        Policy policy = new Policy(0, 0, 0, Policy.getAllSuppressedVisualEffects());

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_OFF, policy, r));
    }

    @Test
    public void testSuppressAnything_bypass_ZenModeOn() {
        NotificationRecord r = getNotificationRecord();
        r.setCriticality(CriticalNotificationExtractor.CRITICAL);
        when(r.getSbn().getPackageName()).thenReturn("bananas");
        Policy policy = new Policy(0, 0, 0, Policy.getAllSuppressedVisualEffects(), 0);

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_NO_INTERRUPTIONS, policy, r));

        r.setCriticality(CriticalNotificationExtractor.CRITICAL_LOW);
        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_NO_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testConversation_allAllowed() {
        Notification n = new Notification.Builder(mContext, "a").build();
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0, n,
                UserHandle.SYSTEM, null, 0);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "me, work");

        NotificationRecord r = getConversationRecord(channel, sbn);
        when(r.isConversation()).thenReturn(true);

        Policy policy = new Policy(
                PRIORITY_CATEGORY_CONVERSATIONS, 0, 0, 0, CONVERSATION_SENDERS_ANYONE);

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testConversation_importantAllowed_isImportant() {
        Notification n = new Notification.Builder(mContext, "a").build();
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0, n,
                UserHandle.SYSTEM, null, 0);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "me, work");
        channel.setImportantConversation(true);

        NotificationRecord r = getConversationRecord(channel, sbn);

        Policy policy = new Policy(
                PRIORITY_CATEGORY_CONVERSATIONS, 0, 0, 0, CONVERSATION_SENDERS_IMPORTANT);

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testConversation_importantAllowed_isNotImportant() {
        Notification n = new Notification.Builder(mContext, "a").build();
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0, n,
                UserHandle.SYSTEM, null, 0);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "me, work");

        NotificationRecord r = getConversationRecord(channel, sbn);

        Policy policy = new Policy(
                PRIORITY_CATEGORY_CONVERSATIONS, 0, 0, 0, CONVERSATION_SENDERS_IMPORTANT);

        assertTrue(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testConversation_noneAllowed_notCallOrMsg() {
        Notification n = new Notification.Builder(mContext, "a").build();
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0, n,
                UserHandle.SYSTEM, null, 0);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "me, work");

        NotificationRecord r = getConversationRecord(channel, sbn);

        Policy policy =
                new Policy(PRIORITY_CATEGORY_CONVERSATIONS, 0, 0, 0, CONVERSATION_SENDERS_NONE);

        assertTrue(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testConversation_noneAllowed_callAllowed() {
        Notification n = new Notification.Builder(mContext, "a").build();
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0, n,
                UserHandle.SYSTEM, null, 0);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "me, work");

        NotificationRecord r = getConversationRecord(channel, sbn);
        when(r.isCategory(CATEGORY_CALL)).thenReturn(true);

        Policy policy =
                new Policy(PRIORITY_CATEGORY_CALLS,
                        PRIORITY_SENDERS_ANY, 0, 0, CONVERSATION_SENDERS_NONE);

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testConversation_noneAllowed_msgAllowed() {
        when(mMessagingUtil.isMessaging(any())).thenReturn(true);
        Notification n = new Notification.Builder(mContext, "a").build();
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0, n,
                UserHandle.SYSTEM, null, 0);

        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "me, work");

        NotificationRecord r = getConversationRecord(channel, sbn);

        Policy policy =
                new Policy(PRIORITY_CATEGORY_MESSAGES,
                        0, PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_NONE);

        assertFalse(mZenModeFiltering.shouldIntercept(ZEN_MODE_IMPORTANT_INTERRUPTIONS, policy, r));
    }

    @Test
    public void testMatchesCallFilter_repeatCallers_directMatch() {
        // after calls given an email with an exact string match, make sure that
        // matchesCallFilter returns the right thing
        String[] mailSource = new String[]{"mailto:hello.world"};
        mZenModeFiltering.recordCall(getNotificationRecordWithPeople(mailSource));

        // set up policy to only allow repeat callers
        Policy policy = new Policy(
                PRIORITY_CATEGORY_REPEAT_CALLERS, 0, 0, 0, CONVERSATION_SENDERS_NONE);

        // check whether matchesCallFilter returns the right thing
        Bundle inputMatches = makeExtrasBundleWithPeople(new String[]{"mailto:hello.world"});
        Bundle inputWrong = makeExtrasBundleWithPeople(new String[]{"mailto:nope"});
        assertTrue(ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                policy, UserHandle.SYSTEM,
                inputMatches, null, 0, 0));
        assertFalse(ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                policy, UserHandle.SYSTEM,
                inputWrong, null, 0, 0));
    }

    @Test
    public void testMatchesCallFilter_repeatCallers_telephoneVariants() {
        // set up telephony manager behavior
        when(mTelephonyManager.getNetworkCountryIso()).thenReturn("us");

        String[] telSource = new String[]{"tel:+1-617-555-1212"};
        mZenModeFiltering.recordCall(getNotificationRecordWithPeople(telSource));

        // set up policy to only allow repeat callers
        Policy policy = new Policy(
                PRIORITY_CATEGORY_REPEAT_CALLERS, 0, 0, 0, CONVERSATION_SENDERS_NONE);

        // cases to test:
        //   - identical number
        //   - same number, different formatting
        //   - different number
        //   - garbage
        Bundle identical = makeExtrasBundleWithPeople(new String[]{"tel:+1-617-555-1212"});
        Bundle same = makeExtrasBundleWithPeople(new String[]{"tel:16175551212"});
        Bundle different = makeExtrasBundleWithPeople(new String[]{"tel:123-456-7890"});
        Bundle garbage = makeExtrasBundleWithPeople(new String[]{"asdfghjkl;"});

        assertTrue("identical numbers should match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                policy, UserHandle.SYSTEM,
                identical, null, 0, 0));
        assertTrue("equivalent but non-identical numbers should match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                policy, UserHandle.SYSTEM,
                same, null, 0, 0));
        assertFalse("non-equivalent numbers should not match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                policy, UserHandle.SYSTEM,
                different, null, 0, 0));
        assertFalse("non-tel strings should not match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                policy, UserHandle.SYSTEM,
                garbage, null, 0, 0));
    }

    @Test
    public void testMatchesCallFilter_repeatCallers_urlEncodedTels() {
        // this is not intended to be a supported case but is one that we have seen
        // sometimes in the wild, so make sure we handle url-encoded telephone numbers correctly
        // when somebody provides one.

        // set up telephony manager behavior
        when(mTelephonyManager.getNetworkCountryIso()).thenReturn("us");

        String[] telSource = new String[]{"tel:%2B16175551212"};
        mZenModeFiltering.recordCall(getNotificationRecordWithPeople(telSource));

        // set up policy to only allow repeat callers
        Policy policy = new Policy(
                PRIORITY_CATEGORY_REPEAT_CALLERS, 0, 0, 0, CONVERSATION_SENDERS_NONE);

        // test cases for various forms of the same phone number and different ones
        Bundle same1 = makeExtrasBundleWithPeople(new String[]{"tel:+1-617-555-1212"});
        Bundle same2 = makeExtrasBundleWithPeople(new String[]{"tel:%2B1-617-555-1212"});
        Bundle same3 = makeExtrasBundleWithPeople(new String[]{"tel:6175551212"});
        Bundle different1 = makeExtrasBundleWithPeople(new String[]{"tel:%2B16175553434"});
        Bundle different2 = makeExtrasBundleWithPeople(new String[]{"tel:+16175553434"});

        assertTrue("same number should match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        policy, UserHandle.SYSTEM,
                        same1, null, 0, 0));
        assertTrue("same number should match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        policy, UserHandle.SYSTEM,
                        same2, null, 0, 0));
        assertTrue("same number should match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        policy, UserHandle.SYSTEM,
                        same3, null, 0, 0));
        assertFalse("different number should not match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        policy, UserHandle.SYSTEM,
                        different1, null, 0, 0));
        assertFalse("different number should not match",
                ZenModeFiltering.matchesCallFilter(mContext, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        policy, UserHandle.SYSTEM,
                        different2, null, 0, 0));
    }
}
