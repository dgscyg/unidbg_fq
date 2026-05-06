package com.mengying.fqnovel.unidbg;

import com.mengying.fqnovel.utils.Texts;
import com.mengying.fqnovel.utils.TempFileUtils;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.SystemPropertyHook;
import com.github.unidbg.linux.android.SystemPropertyProvider;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.VarArg;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.android.dvm.wrapper.DvmBoolean;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.spi.SyscallHandler;
import com.github.unidbg.virtualmodule.android.AndroidModule;
import com.github.unidbg.virtualmodule.android.JniGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import unicorn.Arm64Const;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("unchecked")
public class IdleFQ extends AbstractJni implements IOResolver<AndroidFileIO> {

    private static final Logger log = LoggerFactory.getLogger(IdleFQ.class);

    // 资源路径常量
    private static final String BASE_PATH = "com/dragon/read/oversea/gp";
    private static final String DEFAULT_APK_CLASSPATH = BASE_PATH + "/apk/base.apk";
    private static final String SO_METASEC_ML_PATH = BASE_PATH + "/lib/libmetasec_ml.so";
    private static final String SO_C_SHARE_PATH = BASE_PATH + "/lib/libc++_shared.so";
    private static final String MS_CERT_FILE_PATH = BASE_PATH + "/other/ms_16777218.bin";

    // 应用相关常量
    private static final String PACKAGE_NAME = "com.dragon.read.oversea.gp";
    private static final String APK_INSTALL_PATH = "/data/app/com.dragon.read.oversea.gp-q5NyjSN9BLSTVBJ54kg7YA==/base.apk";
    private static final String DATA_DIR = "/data/user/0/" + PACKAGE_NAME;
    private static final String FILES_DIR = DATA_DIR + "/files";
    private static final String MSDATA_PATH = FILES_DIR + "/.msdata";
    private static final int SDK_VERSION = 23;
    private static final int APP_VERSION_CODE = 68132;
    private static final String APP_VERSION_NAME = "6.8.1.32";
    private static final int ENOENT = 2;
    private static final String BUILD_MODEL = "Pixel 5";
    private static final String BUILD_BRAND = "google";
    private static final String BUILD_MANUFACTURER = "Google";
    private static final String BUILD_DEVICE = "redfin";
    private static final String BUILD_PRODUCT = "redfin";
    private static final String BUILD_HARDWARE = "redfin";
    private static final String BUILD_BOARD = "kona";
    private static final String BUILD_FINGERPRINT = "google/redfin/redfin:13/TQ3A.230805.001/10316531:user/release-keys";
    private static final String BUILD_ID = "TQ3A.230805.001";
    private static final String BUILD_TAGS = "release-keys";
    private static final String BUILD_TYPE = "user";
    private static final Set<String> BLOCKED_PATH_PREFIXES = Set.of(
        "/dev/qemu_pipe",
        "/dev/goldfish_pipe",
        "/dev/socket/qemud",
        "/dev/socket/genyd",
        "/dev/gk_auth",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/sys/qemu_trace"
    );

    private final AndroidEmulator emulator;
    private final Module module;
    private final Memory memory;
    private final boolean loggable;
    private final String apkPath;
    private final String apkClasspath;
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    // 临时文件缓存
    private File tempApkFile;
    private File tempSoMetasecMlFile;
    private File tempSoCShareFile;
    private File tempRootfsDir;
    private File tempMsCertFile;
    private volatile boolean destroyed = false;

    public IdleFQ(boolean loggable, String apkPath, String apkClasspath) {
        this.loggable = loggable;
        this.apkPath = apkPath;
        this.apkClasspath = apkClasspath;
        AndroidEmulator emulatorCandidate = null;
        Memory memoryCandidate = null;
        Module moduleCandidate = null;
        try {
            // 初始化临时文件
            initTempFiles();

            // 创建模拟器
            emulatorCandidate = AndroidEmulatorBuilder
                .for64Bit()
                .setRootDir(tempRootfsDir)
                .setProcessName(PACKAGE_NAME)
                .addBackendFactory(new Unicorn2Factory(true))
                .build();

            // 设置inode和uid
            initEmulatorSettings(emulatorCandidate);

            // 设置系统调用处理器
            SyscallHandler<AndroidFileIO> handler = emulatorCandidate.getSyscallHandler();
            handler.setVerbose(false);
            handler.addIOResolver(this);

            // 初始化内存和VM
            memoryCandidate = emulatorCandidate.getMemory();
            AndroidResolver androidResolver = new AndroidResolver(SDK_VERSION);
            memoryCandidate.setLibraryResolver(androidResolver);
            installSystemPropertyHook(emulatorCandidate, memoryCandidate);

            VM vm = emulatorCandidate.createDalvikVM();
            vm.setJni(this);
            vm.setVerbose(loggable);

            // 导入第三方虚拟模块
            new AndroidModule(emulatorCandidate, vm).register(memoryCandidate);
            new JniGraphics(emulatorCandidate, vm).register(memoryCandidate);

            // 载入依赖so库
            vm.loadLibrary(tempSoCShareFile, false);

            // 初始化JNI对应类
            DvmClass bridgeClass = vm.resolveClass("ms/bd/c/m");
            DvmClass a4a = vm.resolveClass("ms/bd/c/a4$a", bridgeClass);
            vm.resolveClass("com/bytedance/mobsec/metasec/ml/MS", a4a);

            // 加载主要so库
            DalvikModule dm = vm.loadLibrary(tempSoMetasecMlFile, true);
            moduleCandidate = dm.getModule();
            dm.callJNI_OnLoad(emulatorCandidate);

            this.emulator = emulatorCandidate;
            this.memory = memoryCandidate;
            this.module = moduleCandidate;

            // 写入 SDK init 相关内存标志，跳过 MS.a()V 回调链
            patchSdkInitFlags(emulatorCandidate, moduleCandidate);

            // Hook getrandom syscall，sub_1065D8 的 BLR X8 处注入伪随机字节
            hookGetrandom(emulatorCandidate, moduleCandidate);

            log.info("初始化完成");
        } catch (Exception e) {
            cleanupAfterInitFailure(emulatorCandidate);
            log.error("初始化失败", e);
            throw new RuntimeException("初始化失败", e);
        }
    }

    /**
     * 初始化临时文件
     */
    private void initTempFiles() throws IOException {
        try {
            tempApkFile = resolveApkFile();
            tempSoMetasecMlFile = TempFileUtils.getTempFile(SO_METASEC_ML_PATH);
            tempSoCShareFile = TempFileUtils.getTempFile(SO_C_SHARE_PATH);
            tempMsCertFile = TempFileUtils.getTempFile(MS_CERT_FILE_PATH);

            // 处理rootfs目录
            tempRootfsDir = createTempDir("fq_rootfs");
            prepareRootfs(tempRootfsDir.toPath());

            if (tempApkFile == null || !tempApkFile.exists()) {
                throw new IOException("APK 文件不存在或不可用");
            }
            if (loggable) {
                log.debug("临时APK文件: {}", tempApkFile.getAbsolutePath());
                log.debug("临时SO主文件: {}", tempSoMetasecMlFile.getAbsolutePath());
                log.debug("临时SO共享库文件: {}", tempSoCShareFile.getAbsolutePath());
                log.debug("临时证书文件: {}", tempMsCertFile.getAbsolutePath());
                log.debug("临时rootfs目录: {}", tempRootfsDir.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("初始化临时文件失败", e);
            throw new IOException("初始化临时文件失败", e);
        }
    }

    /**
     * 准备模拟器 rootfs 的关键目录/文件，避免 SDK 初始化阶段因路径不存在而失败。
     */
    private void prepareRootfs(Path rootfs) throws IOException {
        Path filesDir = rootfs.resolve(FILES_DIR.substring(1));
        Files.createDirectories(filesDir);

        Path msDataFile = filesDir.resolve(".msdata");
        if (!Files.exists(msDataFile)) {
            Files.createFile(msDataFile);
        }

        Files.createDirectories(rootfs.resolve("data/system"));
        Files.createDirectories(rootfs.resolve("data/app"));
        Files.createDirectories(rootfs.resolve("sdcard/android"));
        Files.createDirectories(rootfs.resolve((DATA_DIR + "/shared_prefs").substring(1)));
        Files.createDirectories(rootfs.resolve((DATA_DIR + "/databases").substring(1)));
        Files.createDirectories(rootfs.resolve("system/bin"));
        Files.createDirectories(rootfs.resolve("system/xbin"));
        Files.createDirectories(rootfs.resolve("dev/socket"));
        Files.createDirectories(rootfs.resolve("proc/self"));

        Files.writeString(rootfs.resolve("system/build.prop"), String.join("\n",
            "ro.build.id=" + BUILD_ID,
            "ro.build.display.id=" + BUILD_ID,
            "ro.build.version.sdk=33",
            "ro.build.version.release=13",
            "ro.build.version.security_patch=2023-08-05",
            "ro.build.fingerprint=" + BUILD_FINGERPRINT,
            "ro.product.brand=" + BUILD_BRAND,
            "ro.product.device=" + BUILD_DEVICE,
            "ro.product.model=" + BUILD_MODEL,
            "ro.product.name=" + BUILD_PRODUCT,
            "ro.hardware=" + BUILD_HARDWARE,
            "ro.board.platform=" + BUILD_BOARD,
            "ro.build.tags=" + BUILD_TAGS,
            "ro.build.type=" + BUILD_TYPE,
            "ro.kernel.qemu=0",
            "ro.debuggable=0",
            "ro.secure=1"
        ) + "\n");
        Files.writeString(rootfs.resolve("proc/cpuinfo"), String.join("\n",
            "Processor\t: AArch64 Processor rev 13 (aarch64)",
            "BogoMIPS\t: 38.40",
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics",
            "Hardware\t: Qualcomm Technologies, Inc SM7250",
            "Serial\t\t: 0000000000000000"
        ) + "\n");
        Files.writeString(rootfs.resolve("proc/self/cmdline"), PACKAGE_NAME + "\0");
    }

    /**
     * 写入 SDK init 标志，跳过 MS.a()V 回调。
     *
     * IDA 逆向：sub_105BBC 检查 byte_34E560，0 → 走 init（触发回调+警告），1 → 跳过。
     * 正常流程会通过 MS.a()V 回调 Java 层再回调 native 完成完整初始化（设置 qword_351788 等），
     * 但伪造 qword_351788 会触发 MSTaskManager::DoLazyInit() 导致未实现 syscall 崩溃。
     * 只设 byte_34E560=1 即可让签名函数正常生成结果。
     *
     * qword_351788 被 32 处 "Fatal: SDK not init" 检查依赖，为 null 时日志警告 + 函数返回 -1。
     * 分配一段伪 init 状态内存并写入指针，绕过全部 null 检查。
     */
    private void patchSdkInitFlags(AndroidEmulator emulator, Module module) {
        long base = module.base;
        // 1. byte_34E560 = 1: 跳过 sub_105BBC init 回调分派
        emulator.getBackend().mem_write(base + 0x34E560, new byte[]{1});

        // 2. qword_351788: 分配伪 init 状态对象，绕过 32 处 "SDK not init" 检查
        Memory memory = emulator.getMemory();
        MemoryBlock fakeState = memory.malloc(0x200, true);
        memory.pointer(base + 0x351788).setLong(0, fakeState.getPointer().peer);

        if (loggable) {
            log.debug("SDK init flags patched: byte_34E560=1, qword_351788=0x{}",
                Long.toHexString(fakeState.getPointer().peer));
        }
    }

    /**
     * 修补 sub_1065D8 中的 getrandom 调用。
     * BLR X8 替换为 MOV X0, X1，跳过 SVC #0x11b syscall。
     * 缓冲区不填充随机数（留零），但避免了 unidbg 崩溃。
     */
    private void hookGetrandom(AndroidEmulator emulator, Module module) {
        long base = module.base;
        // sub_1065D8 BLR X8 → MOV X0, X1 (AA0103E0)
        memory.pointer(base + 0x1065E8L).setInt(0, 0xAA0103E0);
        // sub_1065F8 BLR X8 → MOV X0, X1 (AA0103E0)
        memory.pointer(base + 0x10660CL).setInt(0, 0xAA0103E0);
        if (loggable) {
            log.debug("getrandom BLRs patched at 0x{}, 0x{}",
                Long.toHexString(base + 0x1065E8L),
                Long.toHexString(base + 0x10660CL));
        }
    }

    private File resolveApkFile() throws IOException {
        String configuredApkPath = Texts.trimToNull(apkPath);
        if (configuredApkPath != null) {
            File apkFile = new File(configuredApkPath);
            if (!apkFile.exists() || !apkFile.isFile()) {
                throw new IOException("APK 文件不存在: " + apkFile.getAbsolutePath());
            }
            return apkFile;
        }

        String configuredApkClasspath = Texts.trimToNull(apkClasspath);
        if (configuredApkClasspath != null) {
            File classpathApk = TempFileUtils.getTempFile(configuredApkClasspath);
            if (classpathApk != null && classpathApk.exists()) {
                return classpathApk;
            }
            throw new IOException("未找到 APK classpath 资源: " + configuredApkClasspath);
        }

        File classpathApk = TempFileUtils.getTempFile(DEFAULT_APK_CLASSPATH);
        if (classpathApk != null && classpathApk.exists()) {
            return classpathApk;
        }

        throw new IOException("未找到 APK：请配置 application.unidbg.apk-path（本地文件）或 application.unidbg.apk-classpath（classpath 资源）；默认查找 " + DEFAULT_APK_CLASSPATH);
    }

    /**
     * 创建临时目录
     */
    private File createTempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix).toFile();
    }

    /**
     * 初始化模拟器设置
     */
    private void initEmulatorSettings(AndroidEmulator emulator) {
        Map<String, Integer> iNode = new LinkedHashMap<>();
        iNode.put("/data/system", 671745);
        iNode.put("/data/app", 327681);
        iNode.put("/sdcard/android", 294915);
        iNode.put(DATA_DIR, 655781);
        iNode.put(FILES_DIR, 655864);
        emulator.set("inode", iNode);
        emulator.set("uid", 10074);
    }

    private void installSystemPropertyHook(AndroidEmulator emulator, Memory memory) {
        SystemPropertyHook propertyHook = new SystemPropertyHook(emulator);
        propertyHook.setPropertyProvider(new SystemPropertyProvider() {
            @Override
            public String getProperty(String key) {
                return switch (key) {
                    case "ro.kernel.qemu" -> "0";
                    case "ro.debuggable" -> "0";
                    case "ro.secure" -> "1";
                    case "ro.product.brand" -> BUILD_BRAND;
                    case "ro.product.device" -> BUILD_DEVICE;
                    case "ro.product.model" -> BUILD_MODEL;
                    case "ro.product.name" -> BUILD_PRODUCT;
                    case "ro.product.manufacturer" -> BUILD_MANUFACTURER;
                    case "ro.product.cpu.abi" -> "arm64-v8a";
                    case "ro.product.cpu.abilist" -> "arm64-v8a,armeabi-v7a,armeabi";
                    case "ro.hardware" -> BUILD_HARDWARE;
                    case "ro.board.platform" -> BUILD_BOARD;
                    case "ro.build.fingerprint" -> BUILD_FINGERPRINT;
                    case "ro.build.id", "ro.build.display.id" -> BUILD_ID;
                    case "ro.build.tags" -> BUILD_TAGS;
                    case "ro.build.type" -> BUILD_TYPE;
                    case "ro.build.version.sdk" -> "33";
                    case "ro.build.version.release" -> "13";
                    case "ro.build.version.security_patch" -> "2023-08-05";
                    case "persist.sys.timezone" -> "Asia/Shanghai";
                    case "init.svc.adbd" -> "stopped";
                    case "service.adb.tcp.port" -> "-1";
                    case "ro.serialno" -> "8XV7N15C31000123";
                    default -> null;
                };
            }
        });
        memory.addHookListener(propertyHook);
    }

    /**
     * 生成API请求签名
     *
     * @param url    API请求的URL
     * @param header HTTP请求头信息，格式为key\r\nvalue\r\n的字符串
     * @return 生成的签名字符串，失败时返回null
     */
    public String generateSignature(String url, String header) {
        lifecycleLock.lock();
        try {
            if (destroyed) {
                if (loggable) {
                    log.debug("已销毁，跳过签名生成");
                }
                return null;
            }
            if (loggable) {
                log.debug("准备生成签名 - URL: {}", url);
                log.debug("准备生成签名 - Header: {}", header);
            }

            // 调用native方法生成签名
            Number number = module.callFunction(emulator, 0x168c80, url, header);

            if (number == null) {
                log.error("调用native方法失败，返回结果为null");
                return null;
            }

            // 获取返回结果
            UnidbgPointer result = memory.pointer(number.longValue());
            if (result == null) {
                log.error("获取结果指针失败");
                return null;
            }

            String signature = result.getString(0);

            if (loggable) {
                log.debug("签名生成成功: {}", signature);
            }

            return signature;

        } catch (Exception e) {
            log.error("生成签名过程出错: {}", e.getMessage(), e);
            return null;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 重载方法：使用Map格式的header参数
     *
     * @param url       API请求的URL
     * @param headerMap HTTP请求头的Map，key为header名称，value为header值
     * @return 生成的签名字符串，失败时返回null
     */
    public String generateSignature(String url, Map<String, String> headerMap) {
        if (headerMap == null || headerMap.isEmpty()) {
            return generateSignature(url, "");
        }

        // 将Map转换为\r\n分隔的字符串格式
        StringBuilder headerBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : headerMap.entrySet()) {
            headerBuilder.append(entry.getKey()).append("\r\n")
                .append(entry.getValue()).append("\r\n");
        }

        // 移除最后的\r\n
        String header = headerBuilder.toString();
        if (header.endsWith("\r\n")) {
            header = header.substring(0, header.length() - 2);
        }

        return generateSignature(url, header);
    }

    /**
     * 简化的签名生成方法，只传入URL
     *
     * @param url API请求的URL
     * @return 生成的签名字符串，失败时返回null
     */
    public String generateSignature(String url) {
        return generateSignature(url, "");
    }

    // 环境补充相关方法
    @Override
    public DvmObject<?> callStaticObjectMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        return switch (signature) {
            case "com/bytedance/mobsec/metasec/ml/MS->b(IIJLjava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;" -> {
                int i = vaList.getIntArg(0);
                yield handleMSMethod(vm, i);
            }
            case "java/lang/Thread->currentThread()Ljava/lang/Thread;" ->
                vm.resolveClass("java/lang/Thread").newObject(Thread.currentThread());
            default -> super.callStaticObjectMethodV(vm, dvmClass, signature, vaList);
        };
    }

    /**
     * 处理MS方法调用
     */
    private DvmObject<?> handleMSMethod(BaseVM vm, int methodId) {
        return switch (methodId) {
            case 65539 -> new StringObject(vm, MSDATA_PATH);
            case 33554433, 33554434 -> DvmBoolean.valueOf(vm, false);
            case 16777232 -> vm.resolveClass("java.lang.Integer").newObject(APP_VERSION_CODE);
            case 16777233 -> new StringObject(vm, APP_VERSION_NAME);
            case 16777218 -> {
                try {
                    if (tempMsCertFile != null && tempMsCertFile.exists()) {
                        byte[] fileData = Files.readAllBytes(tempMsCertFile.toPath());
                        if (loggable) {
                            log.debug("成功读取证书文件: {} bytes", fileData.length);
                        }
                        yield new ByteArray(vm, fileData);
                    } else {
                        log.warn("证书文件不存在: {}", tempMsCertFile);
                        yield null;
                    }
                } catch (IOException e) {
                    log.error("读取证书文件失败", e);
                    yield null;
                }
            }
            case 268435470 -> vm.resolveClass("java/lang/Long").newObject(System.currentTimeMillis());
            default -> {
                if (loggable) {
                    log.debug("未处理的MS方法ID: {}", methodId);
                }
                yield null;
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public DvmObject<?> callObjectMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        return switch (signature) {
            case "java/lang/Thread->getStackTrace()[Ljava/lang/StackTraceElement;" -> {
                // 伪造调用栈，避免暴露 unidbg 内部类名（AbstractJni, DvmMethod 等）
                // 真实设备上的调用栈应该只包含 Android SDK 和应用自身的类
                String[] fakeClassNames = {
                    "java.lang.Thread",
                    "com.bytedance.mobsec.metasec.ml.MS",
                    "ms.bd.c.m",
                    "dalvik.system.NativeStart",
                    "com.dragon.read.app.MainApplication",
                    "android.app.ActivityThread",
                    "android.os.Looper",
                };
                String[] fakeMethodNames = {
                    "getStackTrace",
                    "b",
                    "a",
                    "main",
                    "onCreate",
                    "handleBindApplication",
                    "loop",
                };
                DvmObject<?>[] objs = (DvmObject<?>[]) new DvmObject[fakeClassNames.length];
                for (int i = 0; i < fakeClassNames.length; i++) {
                    StackTraceElement fake = new StackTraceElement(fakeClassNames[i], fakeMethodNames[i], fakeClassNames[i].replace('.', '/') + ".java", 100 + i);
                    objs[i] = vm.resolveClass("java/lang/StackTraceElement").newObject(fake);
                }
                yield new ArrayObject(objs);
            }
            case "java/lang/StackTraceElement->getClassName()Ljava/lang/String;" -> {
                StackTraceElement element = (StackTraceElement) dvmObject.getValue();
                String className = element.getClassName();
                yield new StringObject(vm, className);
            }
            case "java/lang/StackTraceElement->getMethodName()Ljava/lang/String;" -> {
                StackTraceElement element = (StackTraceElement) dvmObject.getValue();
                String methodName = element.getMethodName();
                yield new StringObject(vm, methodName);
            }
            case "java/lang/Thread->getBytes(Ljava/lang/String;)[B" -> {
                String arg0 = (String) vaList.getObjectArg(0).getValue();
                if (loggable) {
                    log.debug("java/lang/Thread->getBytes arg0: {}", arg0);
                }
                yield new ByteArray(vm, arg0.getBytes(StandardCharsets.UTF_8));
            }
            default -> super.callObjectMethodV(vm, dvmObject, signature, vaList);
        };
    }

    @Override
    public long callLongMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if ("java/lang/Long->longValue()J".equals(signature)) {
            Object value = dvmObject.getValue();
            if (value instanceof Long l) {
                return l;
            }
        }
        return super.callLongMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public DvmObject<?> getStaticObjectField(BaseVM vm, DvmClass dvmClass, String signature) {
        return switch (signature) {
            case "android/os/Build->MODEL:Ljava/lang/String;" -> new StringObject(vm, BUILD_MODEL);
            case "android/os/Build->BRAND:Ljava/lang/String;" -> new StringObject(vm, BUILD_BRAND);
            case "android/os/Build->MANUFACTURER:Ljava/lang/String;" -> new StringObject(vm, BUILD_MANUFACTURER);
            case "android/os/Build->DEVICE:Ljava/lang/String;" -> new StringObject(vm, BUILD_DEVICE);
            case "android/os/Build->PRODUCT:Ljava/lang/String;" -> new StringObject(vm, BUILD_PRODUCT);
            case "android/os/Build->HARDWARE:Ljava/lang/String;" -> new StringObject(vm, BUILD_HARDWARE);
            case "android/os/Build->BOARD:Ljava/lang/String;" -> new StringObject(vm, BUILD_BOARD);
            case "android/os/Build->FINGERPRINT:Ljava/lang/String;" -> new StringObject(vm, BUILD_FINGERPRINT);
            case "android/os/Build->ID:Ljava/lang/String;" -> new StringObject(vm, BUILD_ID);
            case "android/os/Build->TAGS:Ljava/lang/String;" -> new StringObject(vm, BUILD_TAGS);
            case "android/os/Build->TYPE:Ljava/lang/String;" -> new StringObject(vm, BUILD_TYPE);
            case "android/os/Build$VERSION->RELEASE:Ljava/lang/String;" -> new StringObject(vm, "13");
            case "android/os/Build$VERSION->SECURITY_PATCH:Ljava/lang/String;" -> new StringObject(vm, "2023-08-05");
            default -> super.getStaticObjectField(vm, dvmClass, signature);
        };
    }

    @Override
    public boolean callStaticBooleanMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        return switch (signature) {
            case "android/os/Debug->isDebuggerConnected()Z", "android/os/Debug->waitingForDebugger()Z" -> false;
            default -> super.callStaticBooleanMethodV(vm, dvmClass, signature, vaList);
        };
    }

    @Override
    public int callStaticIntMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        return switch (signature) {
            case "android/os/Process->myUid()I" -> 10074;
            default -> super.callStaticIntMethodV(vm, dvmClass, signature, vaList);
        };
    }

    @Override
    public int getStaticIntField(BaseVM vm, DvmClass dvmClass, String signature) {
        if (loggable) {
            log.debug("getStaticIntField: {}", signature);
        }
        return switch (signature) {
            case "com/bytedance/mobsec/metasec/ml/MS->a()V" -> 0x40;
            case "android/os/Build$VERSION->SDK_INT:I" -> 33;
            default -> {
                if (loggable) {
                    log.debug("未处理的静态整数字段，降级返回0: {}", signature);
                }
                yield 0;
            }
        };
    }

    @Override
    public void callVoidMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        if (loggable) {
            log.debug("callVoidMethod: {}", signature);
        }
        switch (signature) {
            case "com/bytedance/mobsec/metasec/ml/MS->a()V" -> {
                if (loggable) {
                    log.debug("Patched: com/bytedance/mobsec/metasec/ml/MS->a()V");
                }
            }
            default -> super.callVoidMethod(vm, dvmObject, signature, varArg);
        }
    }

    @Override
    public int callIntMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if ("java/lang/Integer->intValue()I".equals(signature)) {
            Object value = dvmObject.getValue();
            if (value instanceof Integer i) {
                return i;
            }
            if (value instanceof String s) {
                return Integer.parseInt(s);
            }
        }
        return super.callIntMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public boolean callBooleanMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if ("java/lang/Boolean->booleanValue()Z".equals(signature)) {
            Object value = dvmObject.getValue();
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof String s) {
                return Boolean.parseBoolean(s);
            }
        }
        return super.callBooleanMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public FileResult resolve(Emulator<AndroidFileIO> emulator, String pathname, int oflags) {
        if (loggable) {
            log.debug("resolve ==> {}", pathname);
        }

        // 处理libmetasec_ml.so文件
        if (pathname.contains("libmetasec_ml.so")) {
            return FileResult.success(new SimpleFileIO(oflags, tempSoMetasecMlFile, pathname));
        }

        // 处理APK文件
        if (pathname.equals(APK_INSTALL_PATH)) {
            return FileResult.success(new SimpleFileIO(oflags, tempApkFile, pathname));
        }

        return null;
    }

    /**
     * 释放资源
     */
    public void destroy() {
        lifecycleLock.lock();
        try {
            if (destroyed) {
                return;
            }
            destroyed = true;
            try {
                emulator.close();
                log.info("资源已释放");
            } catch (Exception e) {
                log.error("关闭模拟器失败", e);
            }
        } finally {
            lifecycleLock.unlock();
        }

        // 高频 reset 时 rootfs 目录会在 /tmp 迅速累积；这里主动清理，避免容器磁盘被占满。
        try {
            deleteRecursively(tempRootfsDir);
        } catch (Exception e) {
            if (loggable) {
                log.debug("清理临时 rootfs 目录失败: {}", tempRootfsDir != null ? tempRootfsDir.getAbsolutePath() : null, e);
            }
        } finally {
            tempRootfsDir = null;
        }
    }

    private void cleanupAfterInitFailure(AndroidEmulator emulatorCandidate) {
        if (emulatorCandidate != null) {
            try {
                emulatorCandidate.close();
            } catch (Exception closeError) {
                if (loggable) {
                    log.debug("初始化失败后关闭模拟器失败", closeError);
                }
            }
        }

        try {
            deleteRecursively(tempRootfsDir);
        } catch (Exception cleanupError) {
            if (loggable) {
                log.debug("初始化失败后清理临时 rootfs 目录失败", cleanupError);
            }
        } finally {
            tempRootfsDir = null;
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception ignored) {
            // ignore
        }
    }
}
