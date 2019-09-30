/*
 * Copyright (c) 2015-2019, Virgil Security, Inc.
 *
 * Lead Maintainer: Virgil Security Inc. <support@virgilsecurity.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     (1) Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *
 *     (2) Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *     (3) Neither the name of virgil nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.virgilsecurity.android.common.manager

import com.virgilsecurity.android.common.callback.OnKeyChangedCallback
import com.virgilsecurity.android.common.exception.FindUsersException
import com.virgilsecurity.android.common.model.FindUsersResult
import com.virgilsecurity.android.common.storage.CardStorage
import com.virgilsecurity.keyknox.utils.unwrapCompanionClass
import com.virgilsecurity.sdk.cards.Card
import com.virgilsecurity.sdk.cards.CardManager
import com.virgilsecurity.sdk.exception.EmptyArgumentException
import java.util.logging.Logger

/**
 * LookupManager
 */
internal class LookupManager internal constructor(
        internal val cardStorage: CardStorage,
        internal val cardManager: CardManager,
        internal val onKeyChangedCallback: OnKeyChangedCallback? = null
) {

    internal fun startUpdateCachedCards() {
        try {
            logger.fine("Updating cached cards started")

            val cardIdsNewest = cardStorage.getNewestCardIds()

            val cardIdsChunked = cardIdsNewest.chunked(MAX_GET_OUTDATED_COUNT)

            for (cardIds in cardIdsChunked) {
                val outdatedIds = cardManager.getOutdated(cardIds)

                for (outdatedId in outdatedIds) {
                    logger.fine("Cached card with id: $outdatedId expired")

                    val outdatedCard =
                            cardStorage.getCard(outdatedId)
                            ?: throw FindUsersException("Card with id: $outdatedId was not found " +
                                                        "locally. Try to call findUsers first")

                    onKeyChangedCallback?.keyChanged(outdatedCard.identity)

                    val newCard = lookupCard(outdatedCard.identity, true)

                    cardStorage.storeCard(newCard)

                    logger.fine("Cached card with id: $outdatedId updated to card " +
                                "with id: ${newCard.identifier}")
                }
            }

            logger.fine("Updating cached card finished")
        } catch (throwable: Throwable) {
            logger.fine("Updating cached cards failed: ${throwable.message}")
        }
    }

    internal fun lookupCachedCards(identities: List<String>): FindUsersResult {
        if (identities.isEmpty()) throw EmptyArgumentException("identities")

        val result: MutableMap<String, Card> = mutableMapOf()
        val cards = cardStorage.searchCards(identities)

        for (identity in identities) {
            val card = cards.firstOrNull { it.identity == identity }
                       ?: throw FindUsersException("Card with identity: $identity was not found " +
                                                   "locally. Try to call findUsers first")

            result[identity] = card
        }

        return FindUsersResult(result)
    }

    internal fun lookupCachedCard(identity: String): Card {
        require(identity.isNotEmpty()) { "\'identity\' should not be empty" }

        val cards = cardStorage.searchCards(listOf(identity))

        if (cards.size >= 2)
            throw FindUsersException("Found duplicated Cards for identity: $identity")

        return cards.firstOrNull()
               ?: throw FindUsersException("Card with identity: $identity was not found " +
                                           "locally. Try to call findUsers first")
    }

    internal fun lookupCards(identities: List<String>,
                             forceReload: Boolean = false): FindUsersResult {
        if (identities.isEmpty()) throw EmptyArgumentException("identities")

        val result: MutableMap<String, Card> = mutableMapOf()
        val identitiesDistincted: MutableList<String> = identities.distinct().toMutableList()

        if (!forceReload) {
            val cards = cardStorage.searchCards(identitiesDistincted)

            val identitiesToRemove = mutableSetOf<String>()
            for (identity in identitiesDistincted) {
                val card = cards.firstOrNull { it.identity == identity }
                if (card != null) {
                    identitiesToRemove.add(identity)
                    result[identity] = card
                }
            }
            identitiesDistincted.removeAll(identitiesToRemove)
        }

        if (identitiesDistincted.isNotEmpty()) {
            val identitiesChunks = identitiesDistincted.chunked(MAX_SEARCH_COUNT)

            for (identitiesChunk in identitiesChunks) {
                val cards = cardManager.searchCards(identitiesChunk)

                for (card in cards) {
                    if (result[card.identity] != null) {
                        throw FindUsersException("Found duplicated Cards for identity: " +
                                                 card.identity)
                    }

                    cardStorage.storeCard(card)
                    result[card.identity] = card
                }
            }
        }

        if (result.keys.toList().sorted() != identities.distinct().sorted()) {
            throw FindUsersException("Card for one or more of provided identities was not found")
        }

        return FindUsersResult(result)
    }

    internal fun lookupCard(identity: String, forceReload: Boolean = false): Card {
        require(identity.isNotEmpty()) { "\'identity\' should not be empty" }

        val cards = lookupCards(listOf(identity), forceReload)

        return cards[identity]
               ?: throw FindUsersException("Card for one or more of provided identities " +
                                           "was not found")
    }

    companion object {
        private const val MAX_SEARCH_COUNT = 50
        private const val MAX_GET_OUTDATED_COUNT = 1_000

        private val logger = Logger.getLogger(unwrapCompanionClass(this.javaClass).name)
    }
}