package com.mengying.fqnovel.unidbg;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.context.Arm64RegisterContext;
import com.github.unidbg.listener.TraceCodeListener;
import com.github.unidbg.memory.Memory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 对 IdleFQ 的 trace 封装：
 * 1. JNI_OnLoad 后 dump 解密后的 SO 内存
 * 2. 使用 unidbg 内置 traceCode 记录签名函数执行流
 * 3. 输出 trace 日志到文件供分析
 */
public class TraceRunner {

    private static final long SIGN_FUNC_OFFSET = 0x168c80L;
    private static final long STACK_CHECK_CALL_OFFSET = 0x17e430L;

    private final IdleFQ idleFQ;
    private final Path outputDir;

    public TraceRunner(IdleFQ idleFQ, Path outputDir) throws IOException {
        this.idleFQ = idleFQ;
        this.outputDir = outputDir;
        Files.createDirectories(outputDir);
    }

    // ===== 1. 内存 Dump =====

    /**
     * dump SO 在模拟器内存中的完整镜像（JNI_OnLoad 解密完成后调用）
     */
    public void dumpSoMemory() throws IOException {
        AndroidEmulator emulator = getEmulator();
        Module module = getModule();
        long base = module.base;
        long size = module.size;

        System.out.printf("[TraceRunner] Dumping SO memory: base=0x%x, size=0x%x(%d KB)%n", base, size, size / 1024);

        byte[] dump = emulator.getBackend().mem_read(base, (int) size);
        Path dumpPath = outputDir.resolve("libmetasec_ml_dumped.bin");
        Files.write(dumpPath, dump);
        System.out.println("[TraceRunner] Dump written to: " + dumpPath.toAbsolutePath());

        // 同时 dump ELF header 信息
        Path infoPath = outputDir.resolve("dump_info.txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(infoPath.toFile()))) {
            pw.printf("base=0x%x%n", base);
            pw.printf("size=0x%x (%d bytes, %d KB)%n", size, size, size / 1024);
            pw.printf("sign_func_offset=0x%x%n", SIGN_FUNC_OFFSET);
            pw.printf("sign_func_addr=0x%x%n", base + SIGN_FUNC_OFFSET);

            // 读 ELF magic
            if (dump.length >= 4) {
                pw.printf("elf_magic=%02x %02x %02x %02x%n",
                        dump[0] & 0xff, dump[1] & 0xff, dump[2] & 0xff, dump[3] & 0xff);
            }

            // 检查签名函数偏移处的字节（对比原始 SO 看是否已解密）
            int off = (int) SIGN_FUNC_OFFSET;
            if (off + 16 <= dump.length) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    sb.append(String.format("%02x ", dump[off + i] & 0xff));
                }
                pw.printf("sign_func_bytes_at_0x%x: %s%n", SIGN_FUNC_OFFSET, sb);
            }
        }
    }

    public void dumpStackCheckStrings() throws IOException {
        Module module = getModule();
        Memory memory = getMemory();
        long tableAddr = module.base + 0x3492c0L;
        Path out = outputDir.resolve("stack_check_strings.txt");

        try (PrintWriter pw = new PrintWriter(new FileWriter(out.toFile()))) {
            pw.printf("table_addr=0x%x%n", tableAddr);
            for (int i = 0; i < 10; i++) {
                long ptr = memory.pointer(tableAddr + i * 8L).getLong(0);
                try {
                    var strPtr = memory.pointer(ptr);
                    String value = strPtr == null ? null : strPtr.getString(0);
                    pw.printf("%d ptr=0x%x value=%s%n", i, ptr, value);
                } catch (Throwable t) {
                    pw.printf("%d ptr=0x%x error=%s%n", i, ptr, t.getClass().getSimpleName());
                }
            }
        }
        System.out.println("[TraceRunner] Stack check strings written to: " + out.toAbsolutePath());
    }

    // ===== 2. 签名函数 Trace =====

    /**
     * 使用 unidbg 内置的 traceCode 跟踪签名函数执行。
     * traceCode 会将每条执行的指令写入 emulator 的 TraceHook 输出。
     */
    public String traceSignature(String url, String header) throws IOException {
        AndroidEmulator emulator = getEmulator();
        Module module = getModule();
        long base = module.base;

        System.out.printf("[TraceRunner] Enabling traceCode for 0x%x%n", SIGN_FUNC_OFFSET);
        System.out.println("[TraceRunner] Calling generateSignature (verbose=true)...");

        dumpStackCheckCallArgs(emulator, module);
        emulator.traceCode(base, base + module.size);

        long startTime = System.currentTimeMillis();

        String result = idleFQ.generateSignature(url, header);

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("[TraceRunner] Signature completed in %d ms%n", elapsed);
        System.out.printf("[TraceRunner] Signature result: %s%n", result);

        return result;
    }

    private void dumpStackCheckCallArgs(AndroidEmulator emulator, Module module) {
        long target = module.base + STACK_CHECK_CALL_OFFSET;
        Path out = outputDir.resolve("stack_check_call_args.txt");
        final boolean[] dumped = {false};

        emulator.traceCode(target, target + 4, new TraceCodeListener() {
            @Override
            public void onInstruction(com.github.unidbg.Emulator<?> emu, long address, capstone.api.Instruction instruction) {
                if (dumped[0]) {
                    return;
                }
                dumped[0] = true;
                Arm64RegisterContext ctx = (Arm64RegisterContext) emu.getContext();
                try (PrintWriter pw = new PrintWriter(new FileWriter(out.toFile()))) {
                    long x0 = ctx.getXLong(0);
                    long x1 = ctx.getXLong(1);
                    long x2 = ctx.getXLong(2);
                    pw.printf("address=0x%x%n", address);
                    pw.printf("x0=0x%x%n", x0);
                    pw.printf("x1=0x%x%n", x1);
                    pw.printf("x2=0x%x%n", x2);

                    dumpCStringArg(pw, emu, "x2_str", x2);
                    dumpPointerArray(pw, emu, "x0_array", x0, 8);
                    dumpMemoryHex(pw, emu, "x0_region", x0 - 0x20, 0xc0);
                    dumpMemoryHex(pw, emu, "x2_region", x2 - 0x20, 0x80);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private void dumpMemoryHex(PrintWriter pw, com.github.unidbg.Emulator<?> emulator, String label, long addr, int size) {
        try {
            byte[] data = emulator.getBackend().mem_read(addr, size);
            pw.printf("%s_addr=0x%x size=0x%x%n", label, addr, size);
            for (int i = 0; i < data.length; i += 16) {
                int end = Math.min(i + 16, data.length);
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (int j = i; j < end; j++) {
                    int b = data[j] & 0xff;
                    hex.append(String.format("%02x ", b));
                    ascii.append(b >= 32 && b <= 126 ? (char) b : '.');
                }
                pw.printf("%s +0x%x: %-48s | %s%n", label, i, hex, ascii);
            }
        } catch (Throwable t) {
            pw.printf("%s_error=%s%n", label, t.getClass().getSimpleName());
        }
    }

    private void dumpCStringArg(PrintWriter pw, com.github.unidbg.Emulator<?> emulator, String label, long addr) {
        try {
            var ptr = getMemory().pointer(addr);
            String value = ptr == null ? null : ptr.getString(0);
            pw.printf("%s=%s%n", label, value);
        } catch (Throwable t) {
            pw.printf("%s_error=%s%n", label, t.getClass().getSimpleName());
        }
    }

    private void dumpPointerArray(PrintWriter pw, com.github.unidbg.Emulator<?> emulator, String label, long addr, int count) {
        Memory memory = getMemory();
        try {
            var basePtr = memory.pointer(addr);
            if (basePtr == null) {
                pw.printf("%s=null%n", label);
                return;
            }
            for (int i = 0; i < count; i++) {
                long ptr = basePtr.getLong(i * 8L);
                pw.printf("%s[%d]=0x%x%n", label, i, ptr);
                dumpCStringArg(pw, emulator, label + "[" + i + "]_str", ptr);
            }
        } catch (Throwable t) {
            pw.printf("%s_error=%s%n", label, t.getClass().getSimpleName());
        }
    }

    private void postProcessTrace(Path traceFile, long base) throws IOException {
        Path funcListFile = outputDir.resolve("trace_unique_funcs.txt");
        try (var lines = Files.lines(traceFile);
             var pw = new PrintWriter(new FileWriter(funcListFile.toFile()))) {
            lines.mapToLong(line -> {
                int idx = line.indexOf("0x");
                if (idx < 0) return -1L;
                try { return Long.parseUnsignedLong(line.substring(idx + 2).split("[^0-9a-fA-F]")[0], 16); }
                catch (Exception e) { return -1L; }
            })
            .filter(addr -> addr >= base)
            .mapToObj(addr -> String.format("0x%x", addr - base))
            .distinct()
            .forEach(pw::println);
        }
        System.out.println("[TraceRunner] Unique function offsets written to: " + funcListFile);
    }

    /**
     * 不使用 traceCode，只用 verbose 模式执行签名（观察 JNI 回调）
     */
    public String traceSignatureJniOnly(String url, String header) {
        System.out.println("[TraceRunner] Calling generateSignature with JNI verbose...");
        long startTime = System.currentTimeMillis();

        String result = idleFQ.generateSignature(url, header);

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("[TraceRunner] Completed in %d ms%n", elapsed);
        System.out.printf("[TraceRunner] Result: %s%n", result);
        return result;
    }

    // ===== Main =====

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get("trace_output");
        System.out.println("========================================");
        System.out.println("[TraceRunner] Output directory: " + outDir.toAbsolutePath());
        System.out.println("========================================");

        // 创建 IdleFQ（verbose=true 打印所有 JNI 调用）
        System.out.println("\n[1/3] Initializing IdleFQ (verbose=true)...");
        IdleFQ fq = new IdleFQ(true, null, null);

        TraceRunner runner = new TraceRunner(fq, outDir);

        // Step 1: dump JNI_OnLoad 后的 SO 内存（此时自解密已完成）
        System.out.println("\n[2/3] Dumping SO memory after JNI_OnLoad...");
        runner.dumpSoMemory();

        // Step 2: 直接生成签名（不用 traceCode）
        System.out.println("\n[3/3] Generating signature (no traceCode)...");
        String testUrl = "https://api5-normal-sinfonlineb.fqnovel.com/content/api/mobile/search/info/v7/?aid=1967&version_code=68132";
        String testHeader = "User-Agent\r\ncom.dragon.read.oversea.gp/68132\r\nCookie\r\nstore-region=cn-zj\r\n";

        String signature = runner.traceSignatureJniOnly(testUrl, testHeader);

        if (signature == null || signature.isEmpty()) {
            System.out.println("\n[WARN] Signature returned null/empty - environment detection may be active!");
        }

        // Step 3: 清理
        fq.destroy();
        System.out.println("\n[TraceRunner] Done. Check " + outDir.toAbsolutePath());
    }

    // ===== 反射访问 IdleFQ 内部字段 =====

    private AndroidEmulator getEmulator() {
        try {
            var field = IdleFQ.class.getDeclaredField("emulator");
            field.setAccessible(true);
            return (AndroidEmulator) field.get(idleFQ);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access emulator field", e);
        }
    }

    private Module getModule() {
        try {
            var field = IdleFQ.class.getDeclaredField("module");
            field.setAccessible(true);
            return (Module) field.get(idleFQ);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access module field", e);
        }
    }

    private Memory getMemory() {
        try {
            var field = IdleFQ.class.getDeclaredField("memory");
            field.setAccessible(true);
            return (Memory) field.get(idleFQ);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access memory field", e);
        }
    }
}