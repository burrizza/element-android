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

package im.vector.app.features.voicebroadcast.usecase

import im.vector.app.features.voicebroadcast.STATE_ROOM_VOICE_BROADCAST_INFO
import im.vector.app.features.voicebroadcast.model.MessageVoiceBroadcastInfoContent
import im.vector.app.features.voicebroadcast.model.VoiceBroadcastState
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import timber.log.Timber
import javax.inject.Inject

class PauseVoiceBroadcastUseCase @Inject constructor(
        private val session: Session,
) {

    suspend fun execute(roomId: String) {
        val room = session.roomService().getRoom(roomId) ?: return

        Timber.d("Pause voice broadcast requested")

        val lastVoiceBroadcastEvent = room.stateService().getStateEvent(
                STATE_ROOM_VOICE_BROADCAST_INFO,
                QueryStringValue.Equals(session.myUserId),
        )
        when (lastVoiceBroadcastEvent?.content.toModel<MessageVoiceBroadcastInfoContent>()?.voiceBroadcastState) {
            VoiceBroadcastState.STARTED,
            VoiceBroadcastState.RESUMED -> pauseVoiceBroadcast(room, lastVoiceBroadcastEvent)
            // TODO return errors
            VoiceBroadcastState.PAUSED -> {
                Timber.d("Cannot pause voice broadcast: already paused")
            }
            VoiceBroadcastState.STOPPED -> {
                Timber.d("Cannot pause voice broadcast: stopped")
            }
            null -> {
                Timber.d("Cannot pause voice broadcast: no voice broadcast found")
            }
        }
    }

    private suspend fun pauseVoiceBroadcast(room: Room, event: Event?) {
        val newContent = MessageVoiceBroadcastInfoContent(
                relatesTo = RelationDefaultContent(RelationType.REPLACE, event?.eventId),
                newContent = MessageVoiceBroadcastInfoContent(
                        voiceBroadcastStateStr = VoiceBroadcastState.PAUSED.value,
                ).toContent()
        ).toContent()

        room.stateService().sendStateEvent(
                eventType = STATE_ROOM_VOICE_BROADCAST_INFO,
                stateKey = session.myUserId,
                body = newContent,
        )

        // TODO pause recording audio files
    }
}
