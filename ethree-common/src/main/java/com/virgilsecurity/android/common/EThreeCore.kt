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

package com.virgilsecurity.android.common

import android.content.Context
import com.virgilsecurity.android.common.callback.OnGetTokenCallback
import com.virgilsecurity.android.common.callback.OnKeyChangedCallback
import com.virgilsecurity.android.common.exception.*
import com.virgilsecurity.android.common.manager.GroupManager
import com.virgilsecurity.android.common.manager.LookupManager
import com.virgilsecurity.android.common.storage.cloud.KeyManagerCloud
import com.virgilsecurity.android.common.storage.cloud.TicketStorageCloud
import com.virgilsecurity.android.common.storage.local.GroupStorageFile
import com.virgilsecurity.android.common.storage.local.KeyStorageLocal
import com.virgilsecurity.android.common.util.Const
import com.virgilsecurity.android.common.util.Const.VIRGIL_BASE_URL
import com.virgilsecurity.android.common.util.Const.VIRGIL_CARDS_SERVICE_PATH
import com.virgilsecurity.android.common.worker.*
import com.virgilsecurity.common.model.Completable
import com.virgilsecurity.common.model.Data
import com.virgilsecurity.keyknox.build.VersionVirgilAgent
import com.virgilsecurity.sdk.cards.Card
import com.virgilsecurity.sdk.cards.CardManager
import com.virgilsecurity.sdk.cards.validation.VirgilCardVerifier
import com.virgilsecurity.sdk.client.HttpClient
import com.virgilsecurity.sdk.client.VirgilCardClient
import com.virgilsecurity.sdk.crypto.HashAlgorithm
import com.virgilsecurity.sdk.crypto.VirgilCardCrypto
import com.virgilsecurity.sdk.crypto.VirgilCrypto
import com.virgilsecurity.sdk.crypto.VirgilKeyPair
import com.virgilsecurity.sdk.jwt.Jwt
import com.virgilsecurity.sdk.jwt.accessProviders.CachingJwtProvider
import com.virgilsecurity.sdk.jwt.contract.AccessTokenProvider
import com.virgilsecurity.sdk.storage.DefaultKeyStorage

/**
 * [EThreeCore] class simplifies work with Virgil Services to easily implement End to End Encrypted
 * communication.
 */
abstract class EThreeCore
/**
 * @constructor Initializing [CardManager] with provided in [EThreeCore.initialize] callback
 * [onGetTokenCallback] using [CachingJwtProvider] also initializing [DefaultKeyStorage] with
 * default settings.
 */
constructor(identity: String,
            getTokenCallback: OnGetTokenCallback,
            keyChangedCallback: OnKeyChangedCallback,
            context: Context) {

    private var accessTokenProvider: AccessTokenProvider
    private val crypto: VirgilCrypto = VirgilCrypto()
    private val cardManager: CardManager
    private val rootPath: String

    private val keyManagerCloud: KeyManagerCloud
    private val lookupManager: LookupManager
    protected abstract val keyStorageLocal: KeyStorageLocal

    private val authorizationWorker: AuthorizationWorker
    private val backupWorker: BackupWorker
    private val groupWorker: GroupWorker
    private val p2pWorker: PeerToPeerWorker
    private val searchWorker: SearchWorker

    private var groupManager: GroupManager? = null

    val identity: String

    init {
        val cardCrypto = VirgilCardCrypto(crypto)
        val virgilCardVerifier = VirgilCardVerifier(cardCrypto)
        val httpClient = HttpClient(Const.ETHREE_NAME, VersionVirgilAgent.VERSION)
        this.accessTokenProvider = CachingJwtProvider { Jwt(getTokenCallback.onGetToken()) }

        cardManager = CardManager(cardCrypto,
                                  accessTokenProvider,
                                  VirgilCardVerifier(cardCrypto, false, false),
                                  VirgilCardClient(VIRGIL_BASE_URL + VIRGIL_CARDS_SERVICE_PATH,
                                                   httpClient))

        keyManagerCloud = KeyManagerCloud(identity,
                                          crypto,
                                          accessTokenProvider,
                                          VersionVirgilAgent.VERSION)

        val cardStorageSqlite: CardStorageSQLiteStub // TODO virgilCardVerifier goes here

        this.lookupManager = LookupManager(cardStorageSqlite, cardManager, keyChangedCallback)
        this.identity = identity
        this.rootPath = context.filesDir.absolutePath
        this.authorizationWorker = AuthorizationWorker(cardManager, crypto, keyStorageLocal)
        this.backupWorker = BackupWorker()
        this.groupWorker = GroupWorker(identity, crypto, ::getGroupManager, ::computeSessionId)
        this.p2pWorker = PeerToPeerWorker(::getGroupManager, keyStorageLocal, crypto)
        this.searchWorker = SearchWorker(lookupManager)

        if (keyStorageLocal.exists()) {
            privateKeyChanged()
        }

        lookupManager.startUpdateCachedCards()
    }

    internal fun getGroupManager(): GroupManager =
            groupManager ?: throw EThreeException("No private key on device. You should call " +
                                                  "register() of retrievePrivateKey()")

    /**
     * Publishes the public key in Virgil's Cards Service in case no public key for current
     * identity is published yet. Otherwise [RegistrationException] will be thrown.
     *
     * To start execution of the current function, please see [Completable] description.
     *
     * @throws RegistrationException
     * @throws CryptoException
     */
    @Synchronized fun register() = authorizationWorker.register()

    /**
     * Revokes the public key for current *identity* in Virgil's Cards Service. After this operation
     * you can call [EThreeCore.register] again.
     *
     * To start execution of the current function, please see [Completable] description.
     *
     * @throws UnRegistrationException if there's no public key published yet, or if there's more
     * than one public key is published.
     */
    @Synchronized fun unregister() = authorizationWorker.unregister()

    /**
     * Generates new key pair, publishes new public key for current identity and deprecating old
     * public key, saves private key to the local storage. All data that was encrypted earlier
     * will become undecryptable.
     *
     * To start execution of the current function, please see [Completable] description.
     *
     * @throws PrivateKeyExistsException
     * @throws CardNotFoundException
     * @throws CryptoException
     */
    @Synchronized fun rotatePrivateKey() = authorizationWorker.rotatePrivateKey()

    /**
     * Checks whether the private key is present in the local storage of current device.
     * Returns *true* if the key is present in the local key storage otherwise *false*.
     */
    fun hasLocalPrivateKey() = authorizationWorker.hasLocalPrivateKey()

    /**
     * ! *WARNING* ! If you call this function after [register] without using [backupPrivateKey]
     * then you loose private key permanently, as well you won't be able to use identity that
     * was used with that private key no more.
     *
     * Cleans up user's private key from a device - call this function when you want to log your
     * user out of the device.
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     */
    fun cleanup() = authorizationWorker.cleanup()

    internal fun privateKeyChanged(newCard: Card? = null) {
        val selfKeyPair = keyStorageLocal.load()

        val localGroupStorage = GroupStorageFile(identity, crypto, selfKeyPair, rootPath)
        val ticketStorageCloud = TicketStorageCloud(acc, keyStorageLocal)

        this.groupManager = GroupManager(localGroupStorage,
                                         ticketStorageCloud,
                                         this.keyStorageLocal,
                                         this.lookupManager,
                                         this.crypto)

        if (newCard != null) {
            this.lookupManager.cardStorage.storeCard(newCard)
        }
    }

    internal fun privateKeyDeleted() {
        groupManager?.localGroupStorage?.reset()
        groupManager = null

        lookupManager.cardStorage.reset()
    }

    internal fun computeSessionId(identifier: Data): Data {
        require(identifier.data.size > 10) { "Group Id length should be > 10" }

        val hash = crypto.computeHash(identifier.data, HashAlgorithm.SHA512)
                .sliceArray(IntRange(0, 31))

        return Data(hash)
    }

    internal fun publishCardThenSaveLocal(keyPair: VirgilKeyPair? = null,
                                          previousCardId: String? = null) {
        val virgilKeyPair = keyPair ?: crypto.generateKeyPair()

        val card = if (previousCardId != null) {
            cardManager.publishCard(virgilKeyPair.privateKey,
                                    virgilKeyPair.publicKey,
                                    this.identity,
                                    previousCardId)
        } else {
            cardManager.publishCard(virgilKeyPair.privateKey,
                                    virgilKeyPair.publicKey,
                                    this.identity)
        }

        val privateKeyData = Data(crypto.exportPrivateKey(virgilKeyPair.privateKey))
        keyStorageLocal.store(privateKeyData)
        privateKeyChanged(card)
    }

    companion object {
        private const val THROTTLE_TIMEOUT = 2 * 1000L // 2 seconds
    }
}
