package com.xero.ca;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.support.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.spec.KeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class ScriptFileStream extends GZIPInputStream {
    /** 重新打开待解密数据流的工厂：挑选密钥时需要对同一个脚本多试几次，每次都要新的流 */
    public interface StreamFactory {
        InputStream open() throws IOException;
    }

    public ScriptFileStream(Context cx, InputStream stream) throws IOException {
        this(getKeyCandidates(cx).get(0), stream);
    }

    private ScriptFileStream(byte[] key, InputStream stream) throws IOException {
        super(new EncryptedInputStream(key, stream));
    }

    public static InputStream fromAsset(Context cx, String fileName) throws IOException {
        return openWithKeyRotation(cx, () -> cx.getAssets().open(fileName));
    }

    public static InputStream fromFile(Context cx, String sourceFile, String signFile, byte[] verify, int versionCode) throws IOException {
        verifyFile(sourceFile, signFile, verify, versionCode);
        return openWithKeyRotation(cx, () -> new FileInputStream(sourceFile));
    }

    /**
     * 逐个候选密钥尝试解密，第一个能通过 GZIP 头校验的即为正确密钥。
     *
     * 为什么不能只用一张证书：APK 一旦用上 v3 签名密钥轮换
     * （proof-of-rotation，或 Android 13+ 的 v3.1 rotation targeting），
     * 不同 Android 版本从 PackageManager 读到的证书就不再相同：
     *   - v3.1 只对 T+ 轮换时：T+ 上是新证书，旧系统上仍是原证书，两者都要能解开同一份脚本；
     *   - 轮换后 v1(JAR)/v2 仍由最初那把密钥签（API 28 以下只认它），v3 用的是新密钥；
     *   - 多签名 APK 的历史格式是「所有证书首尾相接」当密钥。
     * 而 assets/script.js 只能按其中一张证书异或加密，所以这里挨个试。
     */
    private static InputStream openWithKeyRotation(Context cx, StreamFactory factory) throws IOException {
        IOException last = null;
        for (byte[] key : getKeyCandidates(cx)) {
            InputStream stream = factory.open(); // 打不开（如文件不存在）与密钥无关，直接抛出
            try {
                return new ScriptFileStream(key, stream);
            } catch (IOException e) {
                last = e;
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
        throw last != null ? last : new IOException("No available signature key");
    }

    /**
     * 按优先级列出候选密钥：
     * 1. 当前签名证书全部拼接（历史行为，兼容已有脚本）
     * 2. 当前签名证书逐个
     * 3. v3 轮换历史里的证书（旧证书）
     * 4. v1(JAR) 签名证书（API 28 以下唯一来源；轮换后仍是最初那把密钥）
     * 5. 空密钥（脚本未异或，或完全取不到签名信息）
     */
    // GET_SIGNING_CERTIFICATES / SigningInfo 是 API 28 起，下面已用 SDK_INT 判断保护
    @SuppressLint("NewApi")
    private static List<byte[]> getKeyCandidates(Context cx) {
        List<byte[]> current = new ArrayList<>();
        List<byte[]> history = new ArrayList<>();
        List<byte[]> legacy = new ArrayList<>();
        try {
            PackageManager pm = cx.getPackageManager();
            String pkg = cx.getPackageName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signingInfo = info == null ? null : info.signingInfo;
                if (signingInfo != null) {
                    addSignatures(current, signingInfo.getApkContentsSigners());
                    addSignatures(history, signingInfo.getSigningCertificateHistory());
                }
            }
            PackageInfo legacyInfo = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (legacyInfo != null) addSignatures(legacy, legacyInfo.signatures);
        } catch (Exception e) {
            // 取不到签名信息时退化为不异或
        }
        List<byte[]> keys = new ArrayList<>();
        byte[] concatenated = concat(current);
        if (concatenated != null) keys.add(concatenated);
        addKeys(keys, current);
        addKeys(keys, history);
        addKeys(keys, legacy);
        keys.add(new byte[]{0});
        return keys;
    }

    private static void addSignatures(List<byte[]> out, Signature[] signatures) {
        if (signatures == null) return;
        for (Signature signature : signatures) {
            if (signature != null) out.add(signature.toByteArray());
        }
    }

    private static void addKeys(List<byte[]> out, List<byte[]> candidates) {
        for (byte[] key : candidates) {
            if (key == null || key.length == 0) continue;
            boolean exists = false;
            for (byte[] added : out) {
                if (Arrays.equals(added, key)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) out.add(key);
        }
    }

    private static byte[] concat(List<byte[]> list) {
        int size = 0;
        for (byte[] bytes : list) size += bytes.length;
        if (size == 0) return null;
        byte[] result = new byte[size];
        size = 0;
        for (byte[] bytes : list) {
            System.arraycopy(bytes, 0, result, size, bytes.length);
            size += bytes.length;
        }
        return result;
    }

    public static byte[] getStringHash(String string) {
        MessageDigest digest;
        byte[] bytes = string.getBytes();
        try {
            digest = MessageDigest.getInstance("SHA-1");
            return digest.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            return bytes;
        }
    }

    public static Object readScript(String scriptFile, byte[] hash) throws IOException, ClassNotFoundException {
        try (FileInputStream fis = new FileInputStream(scriptFile)) {
            ICodeInputStream iis = new ICodeInputStream(fis);
            return iis.readScript(hash);
        }
    }

    public static void writeScript(String scriptFile, byte[] hash, Object script) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
            ICodeOutputStream ios = new ICodeOutputStream(fos);
            ios.writeScript(hash, script);
        }
    }

    public static Object readICode(byte[] iCode, byte[] hash) throws IOException, ClassNotFoundException {
        Inflater inflater = new Inflater(true);
        ByteArrayInputStream bis = new ByteArrayInputStream(iCode);
        InflaterInputStream iis = new InflaterInputStream(bis, inflater);
        ICodeInputStream ices = new ICodeInputStream(iis);
        return ices.readScript(hash);
    }

    public static byte[] writeICode(Object script, byte[] hash) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(bos, deflater);
        ICodeOutputStream icons = new ICodeOutputStream(dos);
        icons.writeScript(hash, script);
        icons.close();
        return bos.toByteArray();
    }

    private static void verifyFile(String sourceFile, String signFile, byte[] verify, int versionCode) throws IOException {
        try (InputStream is = new FileInputStream(sourceFile)) {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            KeyFactory kf = KeyFactory.getInstance("RSA");
            KeySpec spec = new X509EncodedKeySpec(verify);
            PublicKey pk = kf.generatePublic(spec);
            signature.initVerify(pk);
            byte[] buf = new byte[4096];
            int readBytes;
            while ((readBytes = is.read(buf)) >= 0) {
                signature.update(buf, 0, readBytes);
            }
            byte[] sign = getFileBytes(signFile);
            if (sign.length < 4) throw new SignatureException("Signature is not available");
            int signVer = readIntLE(sign, 0);
            // 允许同版本号的热更新（重打同一版本时不用先抬 versionCode），只拒绝比当前版本更旧的
            if (signVer < versionCode)
                throw new SignatureException("Version " + signVer + " is too low");
            if (!signature.verify(sign, 4, sign.length - 4)) {
                throw new SignatureException("Signature not correct");
            }
        } catch (Exception e) {
            throw new IOException("Verification failed", e);
        }
    }

    private static byte[] getFileBytes(String fileName) throws IOException {
        byte[] buf = new byte[4096];
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (InputStream is = new FileInputStream(fileName)) {
            int readBytes;
            while ((readBytes = is.read(buf)) >= 0) {
                os.write(buf, 0, readBytes);
            }
        }
        return os.toByteArray();
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) |
                ((bytes[offset + 1] & 0xff) << 8) |
                ((bytes[offset + 2] & 0xff) << 16) |
                ((bytes[offset + 3] & 0xff) << 24);
    }

    private static class EncryptedInputStream extends FilterInputStream {
        private int mReadBytes, mMark;
        private byte[] mKeys;

        private EncryptedInputStream(byte[] keys, InputStream stream) {
            super(stream);
            mReadBytes = 0;
            mKeys = (keys == null || keys.length == 0) ? new byte[]{0} : keys;
            mMark = -1;
        }

        /* Bug 修复
         * Android 7.x 无法解码代码
         * 原因：Android 7.x的GZIPInputStream在<init>时
           会调用readHeader，内部调用了int read()。（之前只
           是int read(byte, int, int)读取一个byte[10]）
         * 解决方案：Override int read()
         */
        @Override
        public int read() throws IOException {
            int r = in.read();
            if (r < 0) return r;
            byte b = (byte) r;
            b ^= mKeys[mReadBytes % mKeys.length];
            mReadBytes++;
            r = b < 0 ? b + 256 : b;
            return r;
        }

        @Override
        public int read(@NonNull byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            int r = in.read(b, off, len);
            int end = off + r;
            for (int i = off; i < end; i++, mReadBytes++) {
                b[i] ^= mKeys[mReadBytes % mKeys.length];
            }
            return r;
        }

        @Override
        public long skip(long n) throws IOException {
            long r = in.skip(n);
            mReadBytes += r;
            return r;
        }

        @Override
        public void mark(int readlimit) {
            in.mark(readlimit);
            mMark = mReadBytes;
        }

        @Override
        public void reset() throws IOException {
            in.reset();
            if (mMark >= 0) mReadBytes = mMark;
        }
    }
    
    private static class ICodeInputStream extends ObjectInputStream {
        private static final int VERSION = 0x5;

        ICodeInputStream(InputStream in) throws IOException {
            super(in);
        }

        Object readScript(byte[] hash) throws IOException, ClassNotFoundException {
            if (readInt() != VERSION) {
                throw new IOException("Incompatible ICode");
            }
            int arrayLen = readInt();
            if (arrayLen < 0) {
                throw new IOException("Hash Chunk Length < 0");
            }
            byte[] bytes = new byte[arrayLen];
            readFully(bytes);
            if (!Arrays.equals(bytes, hash)) {
                throw new IOException("Wrong hash");
            }
            return readObject();
        }
    }

    private static class ICodeOutputStream extends ObjectOutputStream {
        private static final int VERSION = 0x5;

        ICodeOutputStream(OutputStream out) throws IOException {
            super(out);
        }

        void writeScript(byte[] hash, Object script) throws IOException {
            writeInt(VERSION);
            writeInt(hash.length);
            write(hash);
            writeObject(script);
        }
    }
}
