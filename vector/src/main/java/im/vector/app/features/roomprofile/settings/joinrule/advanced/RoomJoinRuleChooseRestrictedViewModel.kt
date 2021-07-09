/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.roomprofile.settings.joinrule.advanced

import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.roomprofile.settings.joinrule.toOption
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem

class RoomJoinRuleChooseRestrictedViewModel @AssistedInject constructor(
        @Assisted val initialState: RoomJoinRuleChooseRestrictedState,
        private val session: Session,
        private val vectorPreferences: VectorPreferences
) : VectorViewModel<RoomJoinRuleChooseRestrictedState, RoomJoinRuleChooseRestrictedActions, RoomJoinRuleChooseRestrictedEvents>(initialState) {

    val room = session.getRoom(initialState.roomId)!!

    init {

        viewModelScope.launch {
            session.getRoomSummary(initialState.roomId)?.let { roomSummary ->
                val joinRulesContent = session.getRoom(initialState.roomId)?.getStateEvent(EventType.STATE_ROOM_JOIN_RULES, QueryStringValue.NoCondition)
                        ?.content
                        ?.toModel<RoomJoinRulesContent>()
                val initialAllowList = joinRulesContent?.allowList

//                val others = session.spaceService().getSpaceSummaries(spaceSummaryQueryParams {
//                    memberships = listOf(Membership.JOIN)
//                }).map { it.toMatrixItem() }

                val knownParentSpacesAllowed = mutableListOf<MatrixItem>()
                val unknownAllowedOrRooms = mutableListOf<MatrixItem>()
                initialAllowList.orEmpty().forEach { entry ->
                    val summary = session.getRoomSummary(entry.spaceID)
                    if (summary == null // it's not known by me
                            || summary.roomType != RoomType.SPACE // it's not a space
                            || !roomSummary.flattenParentIds.contains(summary.roomId) // it's not a parent space
                    ) {
                        unknownAllowedOrRooms.add(
                                summary?.toMatrixItem() ?: MatrixItem.RoomItem(entry.spaceID, null, null)
                        )
                    } else {
                        knownParentSpacesAllowed.add(summary.toMatrixItem())
                    }
                }

                val possibleSpaceCandidate = knownParentSpacesAllowed.toMutableList()
                roomSummary.flattenParentIds.mapNotNull {
                    session.getRoomSummary(it)?.toMatrixItem()
                }.forEach {
                    if (!possibleSpaceCandidate.contains(it)) {
                        possibleSpaceCandidate.add(it)
                    }
                }

                val homeServerCapabilities = session.getHomeServerCapabilities()
                var safeRule: RoomJoinRules = joinRulesContent?.joinRules ?: RoomJoinRules.INVITE
                // server is not really checking that, just to be sure let's check
                val restrictedSupportedByThisVersion = homeServerCapabilities
                        .isFeatureSupported(HomeServerCapabilities.ROOM_CAP_RESTRICTED, room.getRoomVersion())
                if (safeRule == RoomJoinRules.RESTRICTED
                        && !restrictedSupportedByThisVersion) {
                    safeRule = RoomJoinRules.INVITE
                }

                val restrictedSupport = homeServerCapabilities.isFeatureSupported(HomeServerCapabilities.ROOM_CAP_RESTRICTED)
                val couldUpgradeToRestricted = when (restrictedSupport) {
                    HomeServerCapabilities.RoomCapabilitySupport.SUPPORTED          -> true
                    HomeServerCapabilities.RoomCapabilitySupport.SUPPORTED_UNSTABLE -> vectorPreferences.labsUseExperimentalRestricted()
                    else                                                            -> false
                }

                val choices = if (restrictedSupportedByThisVersion || couldUpgradeToRestricted) {
                    listOf(
                            RoomJoinRules.INVITE.toOption(false),
                            RoomJoinRules.RESTRICTED.toOption(!restrictedSupportedByThisVersion),
                            RoomJoinRules.PUBLIC.toOption(false)
                    )
                } else {
                    listOf(
                            RoomJoinRules.INVITE.toOption(false),
                            RoomJoinRules.PUBLIC.toOption(false)
                    )
                }

                setState {
                    copy(
                            roomSummary = Success(roomSummary),
                            initialRoomJoinRules = safeRule,
                            currentRoomJoinRules = safeRule,
                            choices = choices,
                            initialAllowList = initialAllowList.orEmpty(),
                            updatedAllowList = initialAllowList.orEmpty().map {
                                session.getRoomSummary(it.spaceID)?.toMatrixItem() ?: MatrixItem.RoomItem(it.spaceID, null, null)
                            },
//                            selectedRoomList = initialAllowList.orEmpty().map { it.spaceID },
                            possibleSpaceCandidate = possibleSpaceCandidate,
                            unknownRestricted = unknownAllowedOrRooms
                    )
                }
            }
        }
    }

    fun checkForChanges() = withState { state ->
        if (state.initialRoomJoinRules != state.currentRoomJoinRules) {
            setState {
                copy(hasUnsavedChanges = true)
            }
            return@withState
        }

        if (state.currentRoomJoinRules == RoomJoinRules.RESTRICTED) {
            val allowDidChange = state.initialAllowList.map { it.spaceID } != state.updatedAllowList.map { it.id }
            setState {
                copy(hasUnsavedChanges = allowDidChange)
            }
            return@withState
        }

        setState {
            copy(hasUnsavedChanges = false)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(initialState: RoomJoinRuleChooseRestrictedState): RoomJoinRuleChooseRestrictedViewModel
    }

    override fun handle(action: RoomJoinRuleChooseRestrictedActions) {
        when (action) {
            is RoomJoinRuleChooseRestrictedActions.FilterWith      -> handleFilter(action)
            is RoomJoinRuleChooseRestrictedActions.ToggleSelection -> handleToggleSelection(action)
            is RoomJoinRuleChooseRestrictedActions.SelectJoinRules -> handleSelectRule(action)
        }.exhaustive
        checkForChanges()
    }

    fun handleSelectRule(action: RoomJoinRuleChooseRestrictedActions.SelectJoinRules) = withState { state ->
        setState {
            copy(
                    currentRoomJoinRules = action.rules
            )
        }
    }

    private fun handleToggleSelection(action: RoomJoinRuleChooseRestrictedActions.ToggleSelection) = withState { state ->
        val selection = state.updatedAllowList.toMutableList()
        if (selection.indexOfFirst { action.matrixItem.id == it.id } != -1) {
            selection.removeAll { it.id == action.matrixItem.id }
        } else {
            selection.add(action.matrixItem)
        }
        val unknownAllowedOrRooms = mutableListOf<MatrixItem>()

        // we would like to keep initial allowed here to show them unchecked
        // to make it easier for users to spot the changes
        val union = mutableListOf<MatrixItem>().apply {
            addAll(
                    state.initialAllowList.map {
                        session.getRoomSummary(it.spaceID)?.toMatrixItem() ?: MatrixItem.RoomItem(it.spaceID, null, null)
                    }
            )
            addAll(selection)
        }.distinctBy { it.id }.sortedBy { it.id }

        union.forEach { entry ->
            val summary = session.getRoomSummary(entry.id)
            if (summary == null) {
                unknownAllowedOrRooms.add(
                        entry
                )
            } else if (summary.roomType != RoomType.SPACE) {
                unknownAllowedOrRooms.add(entry)
            } else if (!state.roomSummary.invoke()!!.flattenParentIds.contains(entry.id)) {
                // it's a space but not a direct parent
                unknownAllowedOrRooms.add(entry)
            } else {
                // nop
            }
        }

        setState {
            copy(
                    updatedAllowList = selection.toList(),
                    unknownRestricted = unknownAllowedOrRooms
            )
        }
    }

    private fun handleFilter(action: RoomJoinRuleChooseRestrictedActions.FilterWith) {
        setState {
            copy(filter = action.filter, filteredResults = Loading())
        }
        viewModelScope.launch {
            if (vectorPreferences.developerMode()) {
                // in developer mode we let you choose any room or space to restrict to
                val filteredCandidates = session.getRoomSummaries(roomSummaryQueryParams {
                    excludeType = null
                    displayName = QueryStringValue.Contains(action.filter, QueryStringValue.Case.INSENSITIVE)
                    memberships = listOf(Membership.JOIN)
                }).map { it.toMatrixItem() }
                setState {
                    copy(
                            filteredResults = Success(filteredCandidates)
                    )
                }
            } else {
                // in normal mode you can only restrict to space parents
                setState {
                    copy(
                            filteredResults = Success(
                                    session.getRoomSummary(initialState.roomId)?.flattenParentIds?.mapNotNull {
                                        session.getRoomSummary(it)?.toMatrixItem()
                                    }?.filter {
                                        it.displayName?.contains(filter, true) == true
                                    }.orEmpty()
                            )
                    )
                }
            }
        }
    }

    companion object : MvRxViewModelFactory<RoomJoinRuleChooseRestrictedViewModel, RoomJoinRuleChooseRestrictedState> {

        override fun create(viewModelContext: ViewModelContext, state: RoomJoinRuleChooseRestrictedState)
                : RoomJoinRuleChooseRestrictedViewModel? {
            val factory = when (viewModelContext) {
                is FragmentViewModelContext -> viewModelContext.fragment as? Factory
                is ActivityViewModelContext -> viewModelContext.activity as? Factory
            }
            return factory?.create(state) ?: error("You should let your activity/fragment implements Factory interface")
        }
    }
}
