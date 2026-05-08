package com.mengying.fqnovel.crypto;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Compare Java TTEncrypt.calculate() against known Python outputs.
 */
public class TTEncryptTest {

    // Python reference outputs (seed=12345)
    static final int[] PY_CALC_EMPTY = {207,131,225,53,126,239,184,189,241,84,40,80,214,109,128,7,214,32,228,5,11,87,21,220,131,244,169,33,211,108,233,206,71,208,209,60,93,133,242,176,255,131,24,210,135,126,236,47,99,185,49,189,71,65,122,129,165,56,50,122,249,39,218,62};
    static final int[] PY_CALC_1234 = {167,201,118,219,23,35,173,180,18,116,23,141,200,46,155,119,121,65,171,32,28,105,222,97,208,242,188,109,39,163,89,143,89,79,167,72,229,13,136,211,194,191,30,44,46,114,195,207,239,120,195,198,212,175,169,3,145,247,227,58,186,188,164,142};
    static final int[] PY_CALC_Z128 = {171,148,47,82,98,114,228,86,237,104,169,121,245,2,2,144,92,169,3,161,65,237,152,68,53,103,177,30,240,191,37,165,82,214,57,5,26,1,190,88,85,129,34,197,142,61,224,125,116,158,229,157,237,54,172,240,197,92,217,25,36,214,186,17};

    public static void main(String[] args) throws Exception {
        TTEncrypt tt = new TTEncrypt();
        Method calc = TTEncrypt.class.getDeclaredMethod("calculate", int[].class);
        calc.setAccessible(true);

        int pass = 0, fail = 0;

        // TEST1: calculate([])
        int[] r1 = (int[]) calc.invoke(tt, (Object) new int[]{});
        if (Arrays.equals(r1, PY_CALC_EMPTY)) { pass++; System.out.println("PASS TEST1 calculate([])"); }
        else { fail++; System.out.println("FAIL TEST1 calculate([])");
            System.out.println("  Java: " + arrStr(r1));
            System.out.println("  Py  : " + arrStr(PY_CALC_EMPTY));
            diffArr(r1, PY_CALC_EMPTY);
        }

        // TEST2: calculate([1,2,3,4])
        int[] r2 = (int[]) calc.invoke(tt, (Object) new int[]{1,2,3,4});
        if (Arrays.equals(r2, PY_CALC_1234)) { pass++; System.out.println("PASS TEST2 calculate([1,2,3,4])"); }
        else { fail++; System.out.println("FAIL TEST2 calculate([1,2,3,4])");
            System.out.println("  Java: " + arrStr(r2));
            System.out.println("  Py  : " + arrStr(PY_CALC_1234));
            diffArr(r2, PY_CALC_1234);
        }

        // TEST3: calculate([0]*128)
        int[] r3 = (int[]) calc.invoke(tt, (Object) new int[128]);
        if (Arrays.equals(r3, PY_CALC_Z128)) { pass++; System.out.println("PASS TEST3 calculate([0]*128)"); }
        else { fail++; System.out.println("FAIL TEST3 calculate([0]*128)");
            System.out.println("  Java: " + arrStr(r3));
            System.out.println("  Py  : " + arrStr(PY_CALC_Z128));
            diffArr(r3, PY_CALC_Z128);
        }

        // TEST4: encrypt/decrypt round-trip
        String json = "{\"magic_tag\":\"ss_app_log\",\"header\":{\"os\":\"Android\"}}";
        byte[] enc = tt.encrypt(json);
        // Check header magic: 0x74 0x63 0x05 0x10 0x00 0x00
        boolean headerOk = (enc[0] == 0x74 && enc[1] == 0x63 && enc[2] == 0x05 && enc[3] == 0x10);
        if (headerOk) { pass++; System.out.println("PASS TEST4 header magic correct"); }
        else { fail++; System.out.println("FAIL TEST4 header magic: " +
            String.format("%02x %02x %02x %02x %02x %02x", enc[0]&0xFF,enc[1]&0xFF,enc[2]&0xFF,enc[3]&0xFF,enc[4]&0xFF,enc[5]&0xFF)); }

        // Decrypt round-trip
        String dec = tt.decrypt(enc);
        if (json.equals(dec)) { pass++; System.out.println("PASS TEST5 encrypt/decrypt round-trip"); }
        else { fail++; System.out.println("FAIL TEST5 round-trip mismatch");
            System.out.println("  Expected: " + json);
            System.out.println("  Got     : " + dec); }

        System.out.println("\n=== Results: " + pass + " passed, " + fail + " failed ===");
    }

    static String arrStr(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(' '); sb.append(a[i]); }
        return sb.toString();
    }

    static void diffArr(int[] java, int[] py) {
        int len = Math.max(java.length, py.length);
        for (int i = 0; i < len; i++) {
            int jv = i < java.length ? java[i] : -1;
            int pv = i < py.length ? py[i] : -1;
            if (jv != pv) System.out.println("  DIFF[" + i + "] Java=" + jv + " Py=" + pv);
        }
    }
}