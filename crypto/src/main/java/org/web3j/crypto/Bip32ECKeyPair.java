/*
 * Copyright 2019 Web3 Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.web3j.crypto;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.bouncycastle.math.ec.ECPoint;

import org.web3j.utils.Numeric;

import static org.web3j.crypto.Hash.hmacSha512;
import static org.web3j.crypto.Hash.sha256hash160;

/**
 * BIP-32 key pair.
 *
 * <p>Adapted from:
 * https://github.com/bitcoinj/bitcoinj/blob/master/core/src/main/java/org/bitcoinj/crypto/DeterministicKey.java
 */
public class Bip32ECKeyPair extends ECKeyPair {
    public static final int HARDENED_BIT = 0x80000000;

    private final boolean parentHasPrivate;
    private final int childNumber;
    private final int depth;
    private final byte[] chainCode;
    private final int parentFingerprint;

    private ECPoint publicKeyPoint;

    public Bip32ECKeyPair(
            final BigInteger privateKey,
            final BigInteger publicKey,
            final int childNumber,
            final byte[] chainCode,
            final Bip32ECKeyPair parent) {
        super(privateKey, publicKey);
        this.parentHasPrivate = parent != null && parent.hasPrivateKey();
        this.childNumber = childNumber;
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.chainCode = Arrays.copyOf(chainCode, chainCode.length);
        this.parentFingerprint = parent != null ? parent.getFingerprint() : 0;
    }

    public static Bip32ECKeyPair create(final BigInteger privateKey, final byte[] chainCode) {
        return new Bip32ECKeyPair(
                privateKey, Sign.publicKeyFromPrivate(privateKey), 0, chainCode, null);
    }

    public static Bip32ECKeyPair create(final byte[] privateKey, final byte[] chainCode) {
        return create(Numeric.toBigInt(privateKey), chainCode);
    }

    public static Bip32ECKeyPair generateKeyPair(final byte[] seed) {
        final byte[] i = hmacSha512("Bitcoin seed".getBytes(), seed);
        final byte[] il = Arrays.copyOfRange(i, 0, 32);
        final byte[] ir = Arrays.copyOfRange(i, 32, 64);
        Arrays.fill(i, (byte) 0);
        final Bip32ECKeyPair keypair = Bip32ECKeyPair.create(il, ir);
        Arrays.fill(il, (byte) 0);
        Arrays.fill(ir, (byte) 0);

        return keypair;
    }

    public static Bip32ECKeyPair deriveKeyPair(final Bip32ECKeyPair master, final int[] path) {
        Bip32ECKeyPair curr = master;
        if (path != null) {
            for (final int childNumber : path) {
                curr = curr.deriveChildKey(childNumber);
            }
        }

        return curr;
    }

    private Bip32ECKeyPair deriveChildKey(final int childNumber) {
        if (!hasPrivateKey()) {
            final byte[] parentPublicKey = getPublicKeyPoint().getEncoded(true);
            final ByteBuffer data = ByteBuffer.allocate(37);
            data.put(parentPublicKey);
            data.putInt(childNumber);
            final byte[] i = hmacSha512(getChainCode(), data.array());
            final byte[] il = Arrays.copyOfRange(i, 0, 32);
            final byte[] chainCode = Arrays.copyOfRange(i, 32, 64);
            Arrays.fill(i, (byte) 0);
            final BigInteger ilInt = new BigInteger(1, il);
            Arrays.fill(il, (byte) 0);
            final ECPoint ki = Sign.publicPointFromPrivate(ilInt).add(getPublicKeyPoint());

            return new Bip32ECKeyPair(
                    null, Sign.publicFromPoint(ki.getEncoded(true)), childNumber, chainCode, this);
        } else {
            final ByteBuffer data = ByteBuffer.allocate(37);
            if (isHardened(childNumber)) {
                data.put(getPrivateKeyBytes33());
            } else {
                final byte[] parentPublicKey = getPublicKeyPoint().getEncoded(true);
                data.put(parentPublicKey);
            }
            data.putInt(childNumber);
            final byte[] i = hmacSha512(getChainCode(), data.array());
            final byte[] il = Arrays.copyOfRange(i, 0, 32);
            final byte[] chainCode = Arrays.copyOfRange(i, 32, 64);
            Arrays.fill(i, (byte) 0);
            final BigInteger ilInt = new BigInteger(1, il);
            Arrays.fill(il, (byte) 0);
            final BigInteger privateKey = getPrivateKey().add(ilInt).mod(Sign.CURVE.getN());

            return new Bip32ECKeyPair(
                    privateKey,
                    Sign.publicKeyFromPrivate(privateKey),
                    childNumber,
                    chainCode,
                    this);
        }
    }

    private int getFingerprint() {
        final byte[] id = getIdentifier();
        return id[3] & 0xFF | (id[2] & 0xFF) << 8 | (id[1] & 0xFF) << 16 | (id[0] & 0xFF) << 24;
    }

    public int getDepth() {
        return depth;
    }

    public int getParentFingerprint() {
        return parentFingerprint;
    }

    public byte[] getChainCode() {
        return chainCode;
    }

    public int getChildNumber() {
        return childNumber;
    }

    private byte[] getIdentifier() {
        return sha256hash160(getPublicKeyPoint().getEncoded(true));
    }

    public ECPoint getPublicKeyPoint() {
        if (publicKeyPoint == null) {
            publicKeyPoint = Sign.publicPointFromPrivate(getPrivateKey());
        }
        return publicKeyPoint;
    }

    public byte[] getPrivateKeyBytes33() {
        final int numBytes = 33;

        final byte[] bytes33 = new byte[numBytes];
        final byte[] priv = bigIntegerToBytes32(getPrivateKey());
        System.arraycopy(priv, 0, bytes33, numBytes - priv.length, priv.length);
        return bytes33;
    }

    private boolean hasPrivateKey() {
        return this.getPrivateKey() != null || parentHasPrivate;
    }

    private static byte[] bigIntegerToBytes32(final BigInteger b) {
        final int numBytes = 32;

        final byte[] src = b.toByteArray();
        final byte[] dest = new byte[numBytes];
        final boolean isFirstByteOnlyForSign = src[0] == 0;
        final int length = isFirstByteOnlyForSign ? src.length - 1 : src.length;
        final int srcPos = isFirstByteOnlyForSign ? 1 : 0;
        final int destPos = numBytes - length;
        System.arraycopy(src, srcPos, dest, destPos, length);
        return dest;
    }

    private static boolean isHardened(final int a) {
        return (a & HARDENED_BIT) != 0;
    }
}
