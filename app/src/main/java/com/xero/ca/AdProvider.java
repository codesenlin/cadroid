package com.xero.ca;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import com.qq.e.ads.splash.SplashAD;
import com.qq.e.ads.splash.SplashADListener;
import com.qq.e.comm.util.AdError;
import com.xero.ca.script.AnalyticsPlatform;

import java.lang.ref.WeakReference;

public class AdProvider {
    /** 广告从开始请求到展示的最长等待时间，超时后按“没有广告”处理，避免一直卡在启动页 */
    private static final int AD_FETCH_TIMEOUT_MS = 10000;

    private static final AdProvider sInstance = new AdProvider();

    public static AdProvider getInstance() {
        return sInstance;
    }

    private final ADListener mADListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mPaid;
    private boolean mCompleted;
    private boolean mPaused;
    private boolean mDispatchWhenResume;
    private long mPresentTime;
    private int mPresentDuration;
    private boolean mAutoSkip;
    private String mMessage;
    private WeakReference<Activity> mActivity;
    private Listener mListener;
    private OnCompleteListener mOnCompleteListener;
    private SplashAD mSplashAD;
    private ViewGroup mContainer;
    private boolean mAdShown;

    public AdProvider() {
        mADListener = new ADListener();
    }

    public void prepare(Activity activity, ViewGroup container) {
        prepare(activity, container, null);
    }

    public void prepare(Activity activity, ViewGroup container, View skipView) {
        prepare(activity, container, skipView, null);
    }

    public void prepare(Activity activity, ViewGroup container, View skipView, Listener listener) {
        mActivity = new WeakReference<>(activity);
        mListener = listener;
        mContainer = container;
        mPaused = false;
        mDispatchWhenResume = false;
        mPresentTime = 0;
        mPresentDuration = -1;
        mAutoSkip = false;
        mAdShown = false;
        mSplashAD = null;
        synchronized (this) {
            mOnCompleteListener = null;
            mCompleted = false;
            mPaid = false;
            mMessage = null;
        }
        mHandler.removeCallbacksAndMessages(null);
        if (Preference.getInstance(activity).checkFirstRun() || !isAdEnabled()) {
            // 首次启动或未配置广告位时不请求广告，直接进入脚本
            mADListener.onADDismissed();
            return;
        }
        // union 4.x 起 AppID 由 GDTAdSdk 初始化传入（见 XApplication），构造参数只保留广告位 ID；
        // 跳过按钮由 SDK 在 container 内自行绘制，不再使用 skipView。
        mSplashAD = new SplashAD(activity, Secret.getGDTPosID(), mADListener, 0);
        mSplashAD.fetchAdOnly();
        if (skipView != null) skipView.setAccessibilityDelegate(new SkipChecker());
        mHandler.postDelayed(mADListener::onADDismissed, AD_FETCH_TIMEOUT_MS);
    }

    private static boolean isAdEnabled() {
        return !TextUtils.isEmpty(Secret.getGDTAppID()) && !TextUtils.isEmpty(Secret.getGDTPosID());
    }

    public void runAfterComplete(OnCompleteListener listener) {
        synchronized (this) {
            if (mCompleted) {
                listener.onComplete(this, mPaid, mMessage);
            } else {
                mOnCompleteListener = listener;
            }
        }
    }

    private void dispatchComplete(boolean success, String message) {
        synchronized (this) {
            if (!mCompleted) {
                mCompleted = true;
                mPaid = success;
                mMessage = message;
                if (mOnCompleteListener != null) mOnCompleteListener.onComplete(this, success, message);
            }
        }
    }

    private void dispatchCompleteCheckSkip(boolean success, String message) {
        if (success && mAutoSkip && mPresentDuration < 3000) {
            success = false;
            message = "点击事件校验失败，请检查开发者选项内是否开启了调试点击";
            reportError(5212, message);
        }
        dispatchComplete(success, message);
    }

    public boolean isCompleted() {
        synchronized (this) {
            return mCompleted;
        }
    }

    public int getPresentDuration() {
        return mPresentDuration;
    }

    public void pause() {
        mPaused = true;
    }

    public void resume() {
        mPaused = false;
        if (mDispatchWhenResume) {
            mDispatchWhenResume = false;
            dispatchCompleteCheckSkip(true, null);
        }
    }

    public void release() {
        mActivity = null;
        mContainer = null;
        mSplashAD = null;
        mHandler.removeCallbacksAndMessages(null);
        dispatchComplete(false, null);
        mListener = null;
        mOnCompleteListener = null;
    }
    
    private void reportError(int errorCode, String errorMsg) {
    	if (mActivity != null) { // GDT似乎有时会在Activity被destroy后调用此方法
            Activity activity = mActivity.get();
            if (activity != null) {
                AnalyticsPlatform.reportAdError(activity, errorCode, errorMsg);
            }
        }
    }

    class ADListener implements SplashADListener {

        @Override
        public void onADDismissed() {
            mHandler.removeCallbacksAndMessages(null);
            mPresentDuration = (int) (SystemClock.uptimeMillis() - mPresentTime);
            if (mPaused) {
                mDispatchWhenResume = true;
            } else {
                dispatchCompleteCheckSkip(true, null);
            }
            if (mListener != null) {
                mListener.onComplete();
            }
        }

        @Override
        public void onNoAD(AdError adError) {
            mHandler.removeCallbacksAndMessages(null);
            int errorCode = adError.getErrorCode();
            boolean adEnabled = errorCode >= 5000;
            reportError(errorCode, adError.getErrorMsg());
            dispatchComplete(adEnabled, adError.getErrorMsg());
        }

        @Override
        public void onADLoaded(long l) {
            // 广告拉取完成，此时才能交给 SDK 展示到 container 中（切回主线程再操作 View）
            mHandler.post(() -> {
                SplashAD ad = mSplashAD;
                ViewGroup container = mContainer;
                if (ad == null || container == null || mAdShown || isCompleted()) return;
                mAdShown = true;
                ad.showAd(container);
            });
        }

        @Override
        public void onADPresent() {
            mPresentTime = SystemClock.uptimeMillis();
            if (mListener != null) {
                mListener.onPresent();
            }
        }

        @Override
        public void onADClicked() {}

        @Override
        public void onADTick(long l) {
            if (mListener != null) {
                mListener.onTick((int) (l / 1000));
            }
        }

        @Override
        public void onADExposure() {}
    }

    class SkipChecker extends View.AccessibilityDelegate {
        @Override
        public boolean performAccessibilityAction(@NonNull View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) { // 被无障碍模拟点击了
                mAutoSkip = true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    }

    interface Listener {
        void onPresent();
        void onTick(int secondsLeft);
        void onComplete();
    }

    interface OnCompleteListener {
        void onComplete(AdProvider provider, boolean success, String message);
    }
}
