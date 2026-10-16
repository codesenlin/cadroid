package com.xero.ca;

import android.util.Base64;

/**
 * 账号/密钥入口。
 *
 * 本文件入库（否则工程无法编译），但**不要**把真实账号值写在这里：
 * 这些值统一从 local.properties 读取，由 app/build.gradle 写进 BuildConfig，
 * 缺省为空 → 对应功能自动关闭。键名：
 *
 *   ca.tdToken              TalkingData 统计 token
 *   ca.gdtAppId             优量汇（GDT）媒体 AppID
 *   ca.gdtPosId             优量汇开屏广告位 ID
 *   ca.giteeClientId        Gitee OAuth 应用 client id
 *   ca.giteeClientSecret    Gitee OAuth 应用 client secret
 *   ca.giteeFeedbackToken   自建反馈平台 token
 */
public class Secret {
    /**
     * 热更新（core.js / core.sign）校验用的 RSA 公钥（X.509 SubjectPublicKeyInfo DER）。
     * 与 app/signatures/privateKey.pem 配对，私钥由 CA 的 cabuild shellBuildRelease
     * （shellWriteHotfixSign）用来给热更新包签名。
     * 这是公钥、不是秘密，所以直接放在仓库里；换签名密钥时同步更新即可。
     */
    private static final String HOTFIX_PUBLIC_KEY =
            "MIIBIDANBgkqhkiG9w0BAQEFAAOCAQ0AMIIBCAKCAQEA1pMZBN7GCySx7cdi4NnYJT4+zWzrHeL/" +
            "Boyo6LyozWvTeG6nCqds5g67D5k1Wf/ZPnepQ+foPUtkuOT+otPmVvHiZ6gbv7IwtXjCBEO+THIY" +
            "uEb1IRWG8DihTonCvjh/jr7Pj8rD2h7jMMnqk9Cnw9xK81AiDVAIBzLggJcX7moFM1nmppTsLLPy" +
            "hKCkZsh6lNg7MQk6ZzcuL2QSwG5tQvFYGN/+A4HMDNRE2mzdw7gkWBlIAbMlZBNPv96YySh3SNv1" +
            "Z2pUDYFUyLvKB7niR1UzEcRrmvdv3uzMjmnnyKLQjngmIJQ/mXJ9PAT+cpkdmd+brjigshd/ox1b" +
            "av7pHwIBAw==";

    public static byte[] getVerifyKey() {
        return Base64.decode(HOTFIX_PUBLIC_KEY, Base64.DEFAULT);
    }

    public static String getGiteeFeedbackToken() {
        return BuildConfig.SECRET_GITEE_FEEDBACK_TOKEN;
    }

    public static String getGiteeClientId() {
        return BuildConfig.SECRET_GITEE_CLIENT_ID;
    }

    public static String getGiteeClientSecret() {
        return BuildConfig.SECRET_GITEE_CLIENT_SECRET;
    }

    public static String getTDToken() {
        return BuildConfig.SECRET_TD_TOKEN;
    }

    /** 优量汇（GDT）媒体 AppID，在优量汇开发者后台的“媒体管理”里获取，为空表示不展示广告 */
    public static String getGDTAppID() {
        return BuildConfig.SECRET_GDT_APP_ID;
    }

    /** 优量汇（GDT）开屏广告位 ID，为空表示不展示广告 */
    public static String getGDTPosID() {
        return BuildConfig.SECRET_GDT_POS_ID;
    }
}
