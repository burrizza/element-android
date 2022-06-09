/*
 * Copyright (c) 2022 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.core.pushers

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.BuildConfig
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.network.WifiDetector
import im.vector.app.core.pushers.model.PushData
import im.vector.app.core.pushers.model.PushDataFcm
import im.vector.app.core.pushers.model.PushDataUnifiedPush
import im.vector.app.core.pushers.model.toPushData
import im.vector.app.core.services.GuardServiceStarter
import im.vector.app.features.notifications.NotifiableEventResolver
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.settings.BackgroundSyncMode
import im.vector.app.features.settings.VectorDataStore
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.util.MatrixJsonParser
import org.unifiedpush.android.connector.MessagingReceiver
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("Push", LoggerTag.SYNC)

/**
 * Hilt injection happen at super.onReceive().
 */
@AndroidEntryPoint
class VectorMessagingReceiver : MessagingReceiver() {
    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var notifiableEventResolver: NotifiableEventResolver
    @Inject lateinit var pushersManager: PushersManager
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var vectorDataStore: VectorDataStore
    @Inject lateinit var wifiDetector: WifiDetector
    @Inject lateinit var guardServiceStarter: GuardServiceStarter
    @Inject lateinit var unifiedPushHelper: UnifiedPushHelper
    @Inject lateinit var unifiedPushStore: UnifiedPushStore

    private val coroutineScope = CoroutineScope(SupervisorJob())

    // UI handler
    private val mUIHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    /**
     * Called when message is received.
     *
     * @param context the Android context
     * @param message the message
     * @param instance connection, for multi-account
     */
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        Timber.tag(loggerTag.value).d("## onMessage() received")

        runBlocking {
            vectorDataStore.incrementPushCounter()
        }

        val pushData = parseData(message) ?: return Unit.also { Timber.tag(loggerTag.value).w("Invalid received data Json format") }

        // Diagnostic Push
        if (pushData.eventId == PushersManager.TEST_EVENT_ID) {
            val intent = Intent(NotificationUtils.PUSH_ACTION)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            return
        }

        if (!vectorPreferences.areNotificationEnabledForDevice()) {
            Timber.tag(loggerTag.value).i("Notification are disabled for this device")
            return
        }

        mUIHandler.post {
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                // we are in foreground, let the sync do the things?
                Timber.tag(loggerTag.value).d("PUSH received in a foreground state, ignore")
            } else {
                onMessageReceivedInternal(pushData)
            }
        }
    }

    /**
     * Parse the received data from Push. Json format are different depending on the source.
     *
     * Notifications received by FCM are formatted by the matrix gateway [1]. The data send to FCM is the content
     * of the "notification" attribute of the json sent to the gateway [2][3].
     * On the other side, with UnifiedPush, the content of the message received is the content posted to the push
     * gateway endpoint [3].
     *
     * *Note*: If we want to get the same content with FCM and unifiedpush, we can do a new sygnal pusher [4].
     *
     * [1] https://github.com/matrix-org/sygnal/blob/main/sygnal/gcmpushkin.py
     * [2] https://github.com/matrix-org/sygnal/blob/main/sygnal/gcmpushkin.py#L366
     * [3] https://spec.matrix.org/latest/push-gateway-api/
     * [4] https://github.com/p1gp1g/sygnal/blob/unifiedpush/sygnal/upfcmpushkin.py (Not tested for a while)
     */
    private fun parseData(message: ByteArray): PushData? {
        val sMessage = String(message)
        if (BuildConfig.LOW_PRIVACY_LOG_ENABLE) {
            Timber.tag(loggerTag.value).d("## onMessage() $sMessage")
        }

        val moshi = MatrixJsonParser.getMoshi()
        return if (unifiedPushHelper.isEmbeddedDistributor()) {
            moshi.adapter(PushDataFcm::class.java).fromJson(sMessage)?.toPushData()
        } else {
            moshi.adapter(PushDataUnifiedPush::class.java).fromJson(sMessage)?.toPushData()
        }
    }

    /**
     * Called if InstanceID token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is also called
     * when the InstanceID token is initially generated, so this is where
     * you retrieve the token.
     */
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Timber.tag(loggerTag.value).i("onNewEndpoint: adding $endpoint")
        if (vectorPreferences.areNotificationEnabledForDevice() && activeSessionHolder.hasActiveSession()) {
            // If the endpoint has changed
            // or the gateway has changed
            if (unifiedPushStore.getEndpointOrToken() != endpoint) {
                unifiedPushStore.storeUpEndpoint(endpoint)
                coroutineScope.launch {
                    unifiedPushHelper.storeCustomOrDefaultGateway(endpoint) {
                        unifiedPushStore.getPushGateway()?.let {
                            pushersManager.enqueueRegisterPusher(endpoint, it)
                        }
                    }
                }
            } else {
                Timber.tag(loggerTag.value).i("onNewEndpoint: skipped")
            }
        }
        val mode = BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_DISABLED
        vectorPreferences.setFdroidSyncBackgroundMode(mode)
        guardServiceStarter.stop()
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        Toast.makeText(context, "Push service registration failed", Toast.LENGTH_SHORT).show()
        val mode = BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_REALTIME
        vectorPreferences.setFdroidSyncBackgroundMode(mode)
        guardServiceStarter.start()
    }

    override fun onUnregistered(context: Context, instance: String) {
        Timber.tag(loggerTag.value).d("Unifiedpush: Unregistered")
        val mode = BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_REALTIME
        vectorPreferences.setFdroidSyncBackgroundMode(mode)
        guardServiceStarter.start()
        runBlocking {
            try {
                pushersManager.unregisterPusher(unifiedPushStore.getEndpointOrToken().orEmpty())
            } catch (e: Exception) {
                Timber.tag(loggerTag.value).d("Probably unregistering a non existing pusher")
            }
        }
    }

    /**
     * Internal receive method.
     *
     * @param pushData Object containing message data.
     */
    private fun onMessageReceivedInternal(pushData: PushData) {
        try {
            if (BuildConfig.LOW_PRIVACY_LOG_ENABLE) {
                Timber.tag(loggerTag.value).d("## onMessageReceivedInternal() : $pushData")
            } else {
                Timber.tag(loggerTag.value).d("## onMessageReceivedInternal()")
            }

            val session = activeSessionHolder.getSafeActiveSession()

            if (session == null) {
                Timber.tag(loggerTag.value).w("## Can't sync from push, no current session")
            } else {
                if (isEventAlreadyKnown(pushData.eventId, pushData.roomId)) {
                    Timber.tag(loggerTag.value).d("Ignoring push, event already known")
                } else {
                    // Try to get the Event content faster
                    Timber.tag(loggerTag.value).d("Requesting event in fast lane")
                    getEventFastLane(session, pushData.roomId, pushData.eventId)

                    Timber.tag(loggerTag.value).d("Requesting background sync")
                    session.syncService().requireBackgroundSync()
                }
            }
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).e(e, "## onMessageReceivedInternal() failed")
        }
    }

    private fun getEventFastLane(session: Session, roomId: String?, eventId: String?) {
        roomId?.takeIf { it.isNotEmpty() } ?: return
        eventId?.takeIf { it.isNotEmpty() } ?: return

        // If the room is currently displayed, we will not show a notification, so no need to get the Event faster
        if (notificationDrawerManager.shouldIgnoreMessageEventInRoom(roomId)) {
            return
        }

        if (wifiDetector.isConnectedToWifi().not()) {
            Timber.tag(loggerTag.value).d("No WiFi network, do not get Event")
            return
        }

        coroutineScope.launch {
            Timber.tag(loggerTag.value).d("Fast lane: start request")
            val event = tryOrNull { session.eventService().getEvent(roomId, eventId) } ?: return@launch

            val resolvedEvent = notifiableEventResolver.resolveInMemoryEvent(session, event, canBeReplaced = true)

            resolvedEvent
                    ?.also { Timber.tag(loggerTag.value).d("Fast lane: notify drawer") }
                    ?.let {
                        notificationDrawerManager.updateEvents { it.onNotifiableEventReceived(resolvedEvent) }
                    }
        }
    }

    // check if the event was not yet received
    // a previous catchup might have already retrieved the notified event
    private fun isEventAlreadyKnown(eventId: String?, roomId: String?): Boolean {
        if (null != eventId && null != roomId) {
            try {
                val session = activeSessionHolder.getSafeActiveSession() ?: return false
                val room = session.getRoom(roomId) ?: return false
                return room.getTimelineEvent(eventId) != null
            } catch (e: Exception) {
                Timber.tag(loggerTag.value).e(e, "## isEventAlreadyKnown() : failed to check if the event was already defined")
            }
        }
        return false
    }
}
