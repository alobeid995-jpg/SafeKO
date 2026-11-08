package com.khalido.safeko;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.PersistableBundle;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {

    private static final int PICK = 1;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String KEY_ALIAS = "safeko_bio_key";
    private static final String PREFS = "safeko_bio";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean picking = false;
    private boolean bioBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Block screenshots and hide the app preview in the recent-apps list
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Never leave the app's own page
                return !HOST.equals(request.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    picking = true;
                    startActivityForResult(Intent.createChooser(i, "Choose backup file"), PICK);
                } catch (Exception e) {
                    picking = false;
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK) {
            picking = false;
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // Lock the vault whenever the app leaves the screen (except while picking a backup file)
    @Override
    protected void onStop() {
        super.onStop();
        if (!picking && web != null) {
            web.evaluateJavascript("if(typeof showLock==='function')showLock();", null);
        }
    }

    // Offer fingerprint unlock whenever the app comes back to the lock screen
    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.evaluateJavascript("if(typeof autoBio==='function')autoBio();", null);
        }
    }

    /* ---------------- fingerprint helpers ---------------- */

    private interface BioCb {
        void ok(Cipher cipher);

        void fail(String msg);
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void deleteKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    private void disableBio() {
        prefs().edit().clear().apply();
        deleteKey();
    }

    private SecretKey createKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            b.setUserAuthenticationValidityDurationSeconds(-1);
        }
        kg.init(b.build());
        return kg.generateKey();
    }

    private SecretKey loadKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    private void prompt(String title, Cipher cipher, final BioCb cb) {
        BiometricPrompt.Builder pb = new BiometricPrompt.Builder(this)
                .setTitle(title)
                .setNegativeButton("Use master password", getMainExecutor(),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                bioBusy = false;
                                cb.fail("");
                            }
                        });
        if (Build.VERSION.SDK_INT >= 30) {
            pb.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        }
        BiometricPrompt p = pb.build();
        p.authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(),
                getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        bioBusy = false;
                        cb.ok(r.getCryptoObject().getCipher());
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        bioBusy = false;
                        // 5 = canceled, 10 = user canceled, 13 = negative button
                        boolean silent = code == 5 || code == 10 || code == 13;
                        cb.fail(silent ? "" : String.valueOf(msg));
                    }
                });
    }

    /* ---------------- JavaScript bridge ---------------- */

    private class Bridge {
        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData cd = ClipData.newPlainText("safe", text);
                    if (Build.VERSION.SDK_INT >= 33) {
                        PersistableBundle pb = new PersistableBundle();
                        pb.putBoolean("android.content.extra.IS_SENSITIVE", true);
                        cd.getDescription().setExtras(pb);
                    }
                    cm.setPrimaryClip(cd);
                }
            });
        }

        @JavascriptInterface
        public boolean saveFile(String name, String content) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                OutputStream o = getContentResolver().openOutputStream(u);
                if (o == null) return false;
                o.write(content.getBytes(StandardCharsets.UTF_8));
                o.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean bioAvailable() {
            try {
                BiometricManager bm = getSystemService(BiometricManager.class);
                if (bm == null) return false;
                int r;
                if (Build.VERSION.SDK_INT >= 30) {
                    r = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                } else {
                    r = bm.canAuthenticate();
                }
                return r == BiometricManager.BIOMETRIC_SUCCESS;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean bioEnabled() {
            return prefs().contains("ct") && prefs().contains("iv");
        }

        @JavascriptInterface
        public void bioDisable() {
            disableBio();
        }

        // Encrypts the master password with a hardware key that needs a fingerprint to use
        @JavascriptInterface
        public void bioEnable(final String masterPassword) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (bioBusy) return;
                    try {
                        disableBio();
                        SecretKey key = createKey();
                        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                        c.init(Cipher.ENCRYPT_MODE, key);
                        final byte[] iv = c.getIV();
                        bioBusy = true;
                        prompt("Turn on fingerprint unlock", c, new BioCb() {
                            @Override
                            public void ok(Cipher cipher) {
                                try {
                                    byte[] ct = cipher.doFinal(masterPassword.getBytes(StandardCharsets.UTF_8));
                                    prefs().edit()
                                            .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                                            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                                            .apply();
                                    js("bioDone('enable',true,'')");
                                } catch (Exception e) {
                                    disableBio();
                                    js("bioDone('enable',false,'Could not turn on fingerprint')");
                                }
                            }

                            @Override
                            public void fail(String msg) {
                                disableBio();
                                js("bioDone('enable',false," + JSONObject.quote(msg) + ")");
                            }
                        });
                    } catch (Exception e) {
                        bioBusy = false;
                        disableBio();
                        js("bioDone('enable',false,'Could not set up fingerprint')");
                    }
                }
            });
        }

        // Asks for a fingerprint, then hands the master password to the page to unlock
        @JavascriptInterface
        public void bioUnlock() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (bioBusy) return;
                    try {
                        String ivS = prefs().getString("iv", null);
                        final String ctS = prefs().getString("ct", null);
                        if (ivS == null || ctS == null) return;
                        SecretKey key = loadKey();
                        if (key == null) {
                            disableBio();
                            js("bioDone('invalid',false,'')");
                            return;
                        }
                        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                        c.init(Cipher.DECRYPT_MODE, key,
                                new GCMParameterSpec(128, Base64.decode(ivS, Base64.NO_WRAP)));
                        bioBusy = true;
                        prompt("Unlock Safe KO", c, new BioCb() {
                            @Override
                            public void ok(Cipher cipher) {
                                try {
                                    byte[] pt = cipher.doFinal(Base64.decode(ctS, Base64.NO_WRAP));
                                    js("bioPassword(" + JSONObject.quote(new String(pt, StandardCharsets.UTF_8)) + ")");
                                } catch (Exception e) {
                                    js("bioDone('unlock',false,'Fingerprint unlock failed')");
                                }
                            }

                            @Override
                            public void fail(String msg) {
                                js("bioDone('unlock',false," + JSONObject.quote(msg) + ")");
                            }
                        });
                    } catch (KeyPermanentlyInvalidatedException e) {
                        bioBusy = false;
                        disableBio();
                        js("bioDone('invalid',false,'')");
                    } catch (Exception e) {
                        bioBusy = false;
                        js("bioDone('unlock',false,'Fingerprint unlock failed')");
                    }
                }
            });
        }
    }
}
