/*
 * Copyright (c) 2015-2018, Virgil Security, Inc.
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

package com.virgilsecurity.ethree;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.virgilsecurity.ethree.interaction.EThree;
import com.virgilsecurity.sdk.cards.CardManager;
import com.virgilsecurity.sdk.cards.model.RawSignedModel;
import com.virgilsecurity.sdk.cards.validation.VirgilCardVerifier;
import com.virgilsecurity.sdk.client.CardClient;
import com.virgilsecurity.sdk.common.TimeSpan;
import com.virgilsecurity.sdk.crypto.*;
import com.virgilsecurity.sdk.crypto.exceptions.CryptoException;
import com.virgilsecurity.sdk.jwt.JwtGenerator;
import com.virgilsecurity.sdk.jwt.accessProviders.GeneratorJwtProvider;
import com.virgilsecurity.sdk.storage.JsonFileKeyStorage;
import com.virgilsecurity.sdk.storage.PrivateKeyStorage;
import com.virgilsecurity.sdk.utils.Tuple;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import utils.TestConfig;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Created by:
 * Danylo Oliinyk
 * on
 * 10/19/18
 * at Virgil Security
 */
@RunWith(AndroidJUnit4.class)
public class EThreeTestPositive {

    private static final int SUCCESS = 1;

    private String identity = UUID.randomUUID().toString();
    private String password = UUID.randomUUID().toString();
    private String tokenString;
    private EThree eThree;

    private JwtGenerator jwtGenerator;

    private Context ctx;

    @Before
    public void setup() {
        this.ctx = InstrumentationRegistry.getTargetContext();
        jwtGenerator = new JwtGenerator(TestConfig.Companion.getAppId(),
                TestConfig.Companion.getApiKey(),
                TestConfig.Companion.getApiPublicKeyId(),
                TimeSpan.fromTime(600, TimeUnit.SECONDS),
                new VirgilAccessTokenSigner(TestConfig.Companion.getVirgilCrypto()));

        try {
            tokenString = jwtGenerator.generateToken(identity).stringRepresentation();
        } catch (CryptoException e) {
            e.printStackTrace();
        }

        EThree.initialize(ctx, new EThree.OnGetTokenCallback() {
            @NotNull
            @Override
            public String onGetToken() {
                return tokenString;
            }
        }, new EThree.OnResultListener<EThree>() {
            @Override
            public void onSuccess(EThree result) {
                eThree = result;
            }

            @Override
            public void onError(@NotNull Throwable throwable) {
                fail(throwable.getMessage());
            }
        });
    }

    private CardManager initCardManager(String identity) {
        VirgilCardCrypto cardCrypto = new VirgilCardCrypto();
        return new CardManager(cardCrypto,
                new GeneratorJwtProvider(jwtGenerator, identity),
                new VirgilCardVerifier(cardCrypto, false, false),
                new CardClient(TestConfig.Companion.getVirgilBaseUrl()
                        + TestConfig.VIRGIL_CARDS_SERVICE_PATH));
    }

    private PrivateKeyStorage initPrivateKeyStorage() {
        return new PrivateKeyStorage(new VirgilPrivateKeyExporter(), new JsonFileKeyStorage());
    }

    private Tuple<VirgilKeyPair, RawSignedModel> generateRawCard(String identity, CardManager cardManager) {
        VirgilCrypto virgilCrypto = new VirgilCrypto();
        try {
            VirgilKeyPair keyPair = virgilCrypto.generateKeys();
            return new Tuple<>(keyPair,
                    cardManager.generateRawCard(keyPair.getPrivateKey(),
                            keyPair.getPublicKey(),
                            identity));
        } catch (CryptoException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Test
    public void bootstrap() {
        final int[] result = new int[1];

        eThree.bootstrap(new EThree.OnCompleteListener() {

            @Override
            public void onSuccess() {
                result[0]++;
            }

            @Override
            public void onError(@NotNull Throwable throwable) {
                fail();
            }
        });

        assertEquals(SUCCESS, result[0]);
    }
}
