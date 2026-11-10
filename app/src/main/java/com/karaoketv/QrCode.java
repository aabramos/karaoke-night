package com.karaoketv;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal QR Code encoder (byte mode, error-correction level M, versions 1-10),
 * written so the app needs no barcode library. Plenty for a URL like
 * http://192.168.100.200:8080/?k=ABCDEFGH. Based on the algorithm in ISO/IEC 18004.
 */
public final class QrCode {

    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_ECC_M = 0;

    public final int size;
    private final boolean[][] modules;     // [y][x], true = dark
    private final boolean[][] isFunction;

    /** Returns null if the text is too long for version 10. */
    public static QrCode encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int ver = 1; ver <= 10; ver++) {
            int capacityBits = numDataCodewords(ver) * 8;
            int countBits = ver <= 9 ? 8 : 16;
            int needed = 4 + countBits + bytes.length * 8;
            if (needed <= capacityBits) return new QrCode(ver, bytes, capacityBits, countBits);
        }
        return null;
    }

    public boolean get(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size && modules[y][x];
    }

    private QrCode(int ver, byte[] bytes, int capacityBits, int countBits) {
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];

        // ---- data bits ----
        BitBuf bb = new BitBuf(capacityBits);
        bb.append(0x4, 4);                 // byte mode
        bb.append(bytes.length, countBits);
        for (byte b : bytes) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacityBits - bb.len));   // terminator
        bb.append(0, (8 - bb.len % 8) % 8);                  // byte align
        for (int pad = 0xEC; bb.len < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);

        byte[] dataCodewords = new byte[capacityBits / 8];
        for (int i = 0; i < bb.len; i++)
            if (bb.bits[i]) dataCodewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));

        drawFunctionPatterns(ver);
        byte[] all = addEccAndInterleave(ver, dataCodewords);
        drawCodewords(all);

        // ---- choose the mask with the lowest penalty ----
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            int p = penalty();
            if (p < bestPenalty) { bestPenalty = p; bestMask = m; }
            applyMask(m);   // XOR again = undo
        }
        applyMask(bestMask);
        drawFormatBits(bestMask);
    }

    // ---------- function patterns ----------

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);

        int[] align = alignmentPositions(ver);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                drawAlignment(align[i], align[j]);
            }
        }
        drawFormatBits(0);   // reserve the area; real bits drawn after masking
        drawVersion(ver);
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setFunction(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_ECC_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));

        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true);   // dark module
    }

    private void drawVersion(int ver) {
        if (ver < 7) return;
        int rem = ver;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = ver << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3, c = i / 3;
            setFunction(a, c, b);
            setFunction(c, a, b);
        }
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int num = ver / 7 + 2;
        int step = (ver * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
        int[] result = new int[num];
        result[0] = 6;
        for (int i = num - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    // ---------- codewords ----------

    private static int rawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int numDataCodewords(int ver) {
        return rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private static byte[] addEccAndInterleave(int ver, byte[] data) {
        int numBlocks = NUM_BLOCKS[ver];
        int eccLen = ECC_PER_BLOCK[ver];
        int raw = rawDataModules(ver) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] divisor = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            byte[] dat = Arrays.copyOfRange(data, k, k + shortLen - eccLen + (i < numShort ? 0 : 1));
            k += dat.length;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = rsRemainder(dat, divisor);
            System.arraycopy(ecc, 0, block, block.length - eccLen, ecc.length);
            blocks[i] = block;
        }
        byte[] result = new byte[raw];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - eccLen || j >= numShort) result[k++] = blocks[j][i];
            }
        }
        return result;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Standard penalty rules N1-N4 (any mask gives a valid code; this just picks a cleaner one). */
    private int penalty() {
        int result = 0;
        // N1: runs of 5+ same-colour modules in rows and columns
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
                    boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) result += 3; else if (run > 5) result++;
                    } else run = 1;
                }
            }
        }
        // N2: 2x2 blocks
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
            }
        // N3: finder-like patterns 1011101 with 4 light modules on one side
        for (int line = 0; line < size; line++)
            for (int start = 0; start + 10 < size; start++) {
                if (finderLike(true, start, line)) result += 40;
                if (finderLike(false, start, line)) result += 40;
            }
        // N4: dark/light balance
        int dark = 0;
        for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
        int total = size * size;
        int k = Math.abs(dark * 20 - total * 10) / total;
        result += k * 10;
        return result;
    }

    private boolean finderLike(boolean horizontal, int start, int line) {
        // pattern A: 1011101 0000, pattern B: 0000 1011101
        final int[] a = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};
        boolean matchA = true, matchB = true;
        for (int i = 0; i < 11; i++) {
            boolean m = horizontal ? modules[line][start + i] : modules[start + i][line];
            if (m != (a[i] == 1)) matchA = false;
            if (m != (a[10 - i] == 1)) matchB = false;
        }
        return matchA || matchB;
    }

    // ---------- Reed-Solomon over GF(2^8) ----------

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class BitBuf {
        final boolean[] bits;
        int len;
        BitBuf(int capacity) { bits = new boolean[capacity]; }
        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--) bits[len++] = ((val >>> i) & 1) != 0;
        }
    }
}
