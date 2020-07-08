package org.bouncycastle.math.ec.custom.djb;

import java.math.BigInteger;
import java.security.SecureRandom;

import org.bouncycastle.math.raw.Nat;
import org.bouncycastle.math.raw.Nat256;
import org.bouncycastle.util.Pack;

public class Curve25519Field
{
    private static final long M = 0xFFFFFFFFL;

    // 2^255 - 19
    static final int[] P = new int[]{ 0xFFFFFFED, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
        0xFFFFFFFF, 0x7FFFFFFF };
    private static final int P7 = 0x7FFFFFFF;
    private static final int[] PExt = new int[]{ 0x00000169, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0xFFFFFFED, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
        0x3FFFFFFF };
    private static final int PInv = 0x13;

    public static void add(int[] x, int[] y, int[] z)
    {
        Nat256.add(x, y, z);
        if (Nat256.gte(z, P))
        {
            subPFrom(z);
        }
    }

    public static void addExt(int[] xx, int[] yy, int[] zz)
    {
        Nat.add(16, xx, yy, zz);
        if (Nat.gte(16, zz, PExt))
        {
            subPExtFrom(zz);
        }
    }

    public static void addOne(int[] x, int[] z)
    {
        Nat.inc(8, x, z);
        if (Nat256.gte(z, P))
        {
            subPFrom(z);
        }
    }

    public static int[] fromBigInteger(BigInteger x)
    {
        int[] z = Nat256.fromBigInteger(x);
        while (Nat256.gte(z, P))
        {
            Nat256.subFrom(P, z);
        }
        return z;
    }

    public static void half(int[] x, int[] z)
    {
        if ((x[0] & 1) == 0)
        {
            Nat.shiftDownBit(8, x, 0, z);
        }
        else
        {
            Nat256.add(x, P, z);
            Nat.shiftDownBit(8, z, 0);
        }
    }

    public static void inv(int[] x, int[] z)
    {
        /*
         * Raise this element to the exponent 2^255 - 21
         *
         * Breaking up the exponent's binary representation into "repunits", we get:
         * { 250 1s } "01011"
         *
         * Therefore we need an addition chain containing 1, 2, 250 (the lengths of the repunits)
         * We use: [1], [2], 3, 5, 10, 15, 25, 50, 75, 125, [250]
         */

        if (0 != isZero(x))
        {
            throw new IllegalArgumentException("'x' cannot be 0");
        }

        int[] x1 = x;
        int[] x2 = Nat256.create();
        square(x1, x2);
        multiply(x2, x1, x2);
        int[] x3 = Nat256.create();
        square(x2, x3);
        multiply(x3, x1, x3);
        int[] x5 = x3;
        squareN(x3, 2, x5);
        multiply(x5, x2, x5);
        int[] x10 = Nat256.create();
        squareN(x5, 5, x10);
        multiply(x10, x5, x10);
        int[] x15 = Nat256.create();
        squareN(x10, 5, x15);
        multiply(x15, x5, x15);
        int[] x25 = x5;
        squareN(x15, 10, x25);
        multiply(x25, x10, x25);
        int[] x50 = x10;
        squareN(x25, 25, x50);
        multiply(x50, x25, x50);
        int[] x75 = x15;
        squareN(x50, 25, x75);
        multiply(x75, x25, x75);
        int[] x125 = x25;
        squareN(x75, 50, x125);
        multiply(x125, x50, x125);
        int[] x250 = x50;
        squareN(x125, 125, x250);
        multiply(x250, x125, x250);

        int[] t = x250;
        squareN(t, 2, t);
        multiply(t, x1, t);
        squareN(t, 3, t);
        multiply(t, x2, z);
    }

    public static int isZero(int[] x)
    {
        int d = 0;
        for (int i = 0; i < 8; ++i)
        {
            d |= x[i];
        }
        d = (d >>> 1) | (d & 1);
        return (d - 1) >> 31;
    }

    public static void multiply(int[] x, int[] y, int[] z)
    {
        int[] tt = Nat256.createExt();
        Nat256.mul(x, y, tt);
        reduce(tt, z);
    }

    public static void multiplyAddToExt(int[] x, int[] y, int[] zz)
    {
        Nat256.mulAddTo(x, y, zz);
        if (Nat.gte(16, zz, PExt))
        {
            subPExtFrom(zz);
        }
    }

    public static void negate(int[] x, int[] z)
    {
        if (0 != isZero(x))
        {
            Nat256.sub(P, P, z);
        }
        else
        {
            Nat256.sub(P, x, z);
        }
    }

    public static void random(SecureRandom r, int[] z)
    {
        byte[] bb = new byte[8 * 4];
        do
        {
            r.nextBytes(bb);
            Pack.littleEndianToInt(bb, 0, z, 0, 8);
            z[7] &= P7;
        }
        while (0 == Nat.lessThan(8, z, P));
    }

    public static void randomMult(SecureRandom r, int[] z)
    {
        do
        {
            random(r, z);
        }
        while (0 != isZero(z));
    }

    public static void reduce(int[] xx, int[] z)
    {
//        assert xx[15] >>> 30 == 0;

        int xx07 = xx[7];
        Nat.shiftUpBit(8, xx, 8, xx07, z, 0);
        int c = Nat256.mulByWordAddTo(PInv, xx, z) << 1;
        int z7 = z[7];
        c += (z7 >>> 31) - (xx07 >>> 31);
        z7 &= P7;
        z7 += Nat.addWordTo(7, c * PInv, z);
        z[7] = z7;
        if (Nat256.gte(z, P))
        {
            subPFrom(z);
        }
    }

    public static void reduce27(int x, int[] z)
    {
//        assert x >>> 26 == 0;

        int z7 = z[7];
        int c = (x << 1 | z7 >>> 31);
        z7 &= P7;
        z7 += Nat.addWordTo(7, c * PInv, z);
        z[7] = z7;
        if (Nat256.gte(z, P))
        {
            subPFrom(z);
        }
    }

    public static void square(int[] x, int[] z)
    {
        int[] tt = Nat256.createExt();
        Nat256.square(x, tt);
        reduce(tt, z);
    }

    public static void squareN(int[] x, int n, int[] z)
    {
//        assert n > 0;

        int[] tt = Nat256.createExt();
        Nat256.square(x, tt);
        reduce(tt, z);

        while (--n > 0)
        {
            Nat256.square(z, tt);
            reduce(tt, z);
        }
    }

    public static void subtract(int[] x, int[] y, int[] z)
    {
        int c = Nat256.sub(x, y, z);
        if (c != 0)
        {
            addPTo(z);
        }
    }

    public static void subtractExt(int[] xx, int[] yy, int[] zz)
    {
        int c = Nat.sub(16, xx, yy, zz);
        if (c != 0)
        {
            addPExtTo(zz);
        }
    }

    public static void twice(int[] x, int[] z)
    {
        Nat.shiftUpBit(8, x, 0, z);
        if (Nat256.gte(z, P))
        {
            subPFrom(z);
        }
    }

    private static int addPTo(int[] z)
    {
        long c = (z[0] & M) - PInv;
        z[0] = (int)c;
        c >>= 32;
        if (c != 0)
        {
            c = Nat.decAt(7, z, 1);
        }
        c += (z[7] & M) + ((P7 + 1) & M);
        z[7] = (int)c;
        c >>= 32;
        return (int)c;
    }

    private static int addPExtTo(int[] zz)
    {
        long c = (zz[0] & M) + (PExt[0] & M);
        zz[0] = (int)c;
        c >>= 32;
        if (c != 0)
        {
            c = Nat.incAt(8, zz, 1);
        }
        c += (zz[8] & M) - PInv;
        zz[8] = (int)c;
        c >>= 32;
        if (c != 0)
        {
            c = Nat.decAt(15, zz, 9);
        }
        c += (zz[15] & M) + ((PExt[15] + 1) & M);
        zz[15] = (int)c;
        c >>= 32;
        return (int)c;
    }

    private static int subPFrom(int[] z)
    {
        long c = (z[0] & M) + PInv;
        z[0] = (int)c;
        c >>= 32;
        if (c != 0)
        {
            c = Nat.incAt(7, z, 1);
        }
        c += (z[7] & M) - ((P7 + 1) & M);
        z[7] = (int)c;
        c >>= 32;
        return (int)c;
    }

    private static int subPExtFrom(int[] zz)
    {
        long c = (zz[0] & M) - (PExt[0] & M);
        zz[0] = (int)c;
        c >>= 32;
        if (c != 0)
        {
            c = Nat.decAt(8, zz, 1);
        }
        c += (zz[8] & M) + PInv;
        zz[8] = (int)c;
        c >>= 32;
        if (c != 0)
        {
            c = Nat.incAt(15, zz, 9);
        }
        c += (zz[15] & M) - ((PExt[15] + 1) & M);
        zz[15] = (int)c;
        c >>= 32;
        return (int)c;
    }
}
