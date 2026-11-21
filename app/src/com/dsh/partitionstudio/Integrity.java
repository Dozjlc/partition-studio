package com.dsh.partitionstudio;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class Integrity {
    private static final String EXPECTED_SIGNER =
            "7c533c47dfbc26c78498cca58e25a459e02f7df8a3b012fff5de0df309311104";
    private static final int PAYLOAD_XOR = 0x6D;

    private Integrity() {}

    static boolean verify(Context context) {
        try {
            if (!verifySigner(context)) return false;
            return verifyPayload(context);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verifySigner(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo info = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signing = info.signingInfo;
            if (signing == null) return false;
            signatures = signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners()
                    : signing.getSigningCertificateHistory();
        } else {
            PackageInfo info = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            signatures = info.signatures;
        }
        if (signatures == null) return false;
        for (Signature signature : signatures) {
            if (EXPECTED_SIGNER.equalsIgnoreCase(sha256(signature.toByteArray()))) return true;
        }
        return false;
    }

    private static boolean verifyPayload(Context context) throws Exception {
        byte[] encrypted;
        try (InputStream in = context.getAssets().open("i.bin");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            encrypted = out.toByteArray();
        }
        for (int i = 0; i < encrypted.length; i++) encrypted[i] ^= PAYLOAD_XOR;
        String[] expected = new String(encrypted, "UTF-8").split("\\n");
        if (expected.length < 4) return false;

        String apkPath = context.getApplicationInfo().sourceDir;
        try (ZipFile zip = new ZipFile(apkPath)) {
            String dex = hashEntry(zip, "classes.dex");
            String res = hashEntry(zip, "resources.arsc");
            String manifest = hashEntry(zip, "AndroidManifest.xml");
            String qr = hashEntry(zip, "assets/d.bin");
            return expected[0].equalsIgnoreCase(dex)
                    && expected[1].equalsIgnoreCase(res)
                    && expected[2].equalsIgnoreCase(manifest)
                    && expected[3].equalsIgnoreCase(qr);
        }
    }

    private static String hashEntry(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return "";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] out) {
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
