package com.mengying.fqnovel.crypto;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Debug: compare hex_27E and hex_30A intermediate outputs with Python reference.
 */
public class TTEncryptDebug {

    public static void main(String[] args) throws Exception {
        TTEncrypt tt = new TTEncrypt();
        Method hex27E = TTEncrypt.class.getDeclaredMethod("hex_27E", int[].class);
        hex27E.setAccessible(true);
        Method hex30A = TTEncrypt.class.getDeclaredMethod("hex_30A", int[].class, int[].class);
        hex30A.setAccessible(true);

        // Build param_list for empty input (same as calculate([]))
        // Padding: 128 bytes, first byte 0x80, rest 0
        int[] tmp_list = new int[128];
        tmp_list[0] = 0x80;

        int[] param_list = new int[32];
        for (int j = 0; j < 32; j++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 4; k++) {
                String hex = Integer.toHexString(tmp_list[4 * j + k] & 0xFF);
                if (hex.length() < 2) hex = "0" + hex;
                sb.append(hex);
            }
            param_list[j] = (int) Long.parseLong(sb.toString(), 16);
        }

        System.out.println("param_list[0:8]: " + arrStr(param_list, 0, 8));

        // Call hex_27E
        int[] hex27E_result = (int[]) hex27E.invoke(tt, (Object) param_list);
        System.out.println("hex_27E len=" + hex27E_result.length);
        System.out.println("hex_27E[0:16]: " + arrStrUnsigned(hex27E_result, 0, 16));
        System.out.println("hex_27E[32:48]: " + arrStrUnsigned(hex27E_result, 32, 48));
        System.out.println("hex_27E[48:64]: " + arrStrUnsigned(hex27E_result, 48, 64));

        // Python reference for hex_27E[32:48]
        long[] pyRef_32_48 = {2147483648L, 0, 0, 0, 33558528L, 4, 0, 0, 268959744L, 33554464L, 0, 0, 2147754497L, 268960064L, 2147483648L, 0};
        System.out.println("Py ref  [32:48]: " + longArrStr(pyRef_32_48));
        boolean match32 = true;
        for (int i = 0; i < 16; i++) {
            if ((hex27E_result[32+i] & 0xFFFFFFFFL) != pyRef_32_48[i]) { match32 = false; break; }
        }
        System.out.println("hex_27E[32:48] match Python: " + match32);

        // Call hex_30A with LIST_6B0 and hex_27E result
        // Access LIST_6B0 via reflection
        java.lang.reflect.Field list6b0Field = TTEncrypt.class.getDeclaredField("LIST_6B0");
        list6b0Field.setAccessible(true);
        int[] LIST_6B0 = (int[]) list6b0Field.get(null);
        int[] list_6B0_copy = Arrays.copyOf(LIST_6B0, LIST_6B0.length);

        System.out.println("\nLIST_6B0: " + arrStrUnsigned(list_6B0_copy, 0, 16));

        int[] hex30A_result = (int[]) hex30A.invoke(tt, list_6B0_copy, hex27E_result);
        System.out.println("hex_30A result: " + arrStrUnsigned(hex30A_result, 0, 16));

        // Python reference for hex_30A
        long[] pyRef_30A = {2129639613L, 3481526581L, 3597500423L, 4048824400L, 190256604L, 3592479749L, 3547130318L, 2213849377L, 1569059504L, 1204867388L, 2273242159L, 4286781650L, 1195473537L, 1673081277L, 4180138558L, 2771923578L};
        System.out.println("Py ref 30A    : " + longArrStr(pyRef_30A));
        boolean match30A = true;
        for (int i = 0; i < 16; i++) {
            if ((hex30A_result[i] & 0xFFFFFFFFL) != pyRef_30A[i]) { match30A = false; break; }
        }
        System.out.println("hex_30A match Python: " + match30A);

        // If hex_30A doesn't match, find first divergence in hex_27E
        if (!match30A) {
            // Check hex_27E more thoroughly
            long[] pyRef_48_64 = {34148480L, 4196352L, 67117056L, 8, 289447939L, 538001448L, 806879232L, 100663392L, 2722431153L, 403316802L, 1083396L, 1075840256L, 3567863234L, 2754021781L, 1135415936L, 20981776L};
            boolean match48 = true;
            for (int i = 0; i < 16; i++) {
                if ((hex27E_result[48+i] & 0xFFFFFFFFL) != pyRef_48_64[i]) { match48 = false; System.out.println("  DIFF hex_27E[" + (48+i) + "] Java=" + (hex27E_result[48+i] & 0xFFFFFFFFL) + " Py=" + pyRef_48_64[i]); break; }
            }
            System.out.println("hex_27E[48:64] match Python: " + match48);
        }
    }

    static String arrStr(int[] a, int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = from; i < to; i++) { if (i > from) sb.append(", "); sb.append(a[i]); }
        return sb.append("]").toString();
    }

    static String arrStrUnsigned(int[] a, int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = from; i < to; i++) { if (i > from) sb.append(", "); sb.append(a[i] & 0xFFFFFFFFL); }
        return sb.append("]").toString();
    }

    static String longArrStr(long[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(a[i]); }
        return sb.append("]").toString();
    }
}