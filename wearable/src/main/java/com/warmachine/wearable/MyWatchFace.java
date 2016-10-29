package com.warmachine.wearable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormatSymbols;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


public class MyWatchFace extends CanvasWatchFaceService {

    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }


    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {


        public class GetBitmapForWeatherTask extends AsyncTask<Asset, Void, Void> {

            @Override
            protected Void doInBackground(Asset... assets) {
                Asset asset = assets[0];
                mIcon = loadBitmapFromAsset(asset);

                int size = Double.valueOf(MyWatchFace.this.getResources().getDimension(R.dimen.icon_size)).intValue();
                mIcon = Bitmap.createScaledBitmap(mIcon, size, size, false);
                postInvalidate();

                return null;
            }
        }

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        boolean mRegisteredTimeZoneReceiver = false;

        Bitmap mIcon;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mIconPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mYOffset;
        String[] mDayNames;
        String[] mMonthNames;
        String mLowTemp;
        String mHighTemp;
        GoogleApiClient mGoogleApiClient;
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.y_offset);
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.blue));
            mTimePaint = new Paint();
            mTimePaint.setColor(resources.getColor(R.color.digital_text_white));
            mTimePaint.setTypeface(NORMAL_TYPEFACE);
            mTimePaint.setAntiAlias(true);
            mDatePaint = new Paint();
            mDatePaint.setColor(resources.getColor(R.color.digital_text_white));
            mDatePaint.setTypeface(NORMAL_TYPEFACE);
            mDatePaint.setAntiAlias(true);
            mIconPaint = new Paint();
            mHighTempPaint = new Paint();
            mHighTempPaint.setColor(resources.getColor(R.color.digital_text_white));
            mHighTempPaint.setTypeface(NORMAL_TYPEFACE);
            mHighTempPaint.setAntiAlias(true);
            mLowTempPaint = new Paint();
            mLowTempPaint.setColor(resources.getColor(R.color.digital_text_white));
            mLowTempPaint.setTypeface(NORMAL_TYPEFACE);
            mLowTempPaint.setAntiAlias(true);
            mTime = new Time();
            DateFormatSymbols symbols = new DateFormatSymbols();
            mDayNames = symbols.getShortWeekdays();
            mMonthNames = symbols.getShortMonths();
            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.time_text_size_round : R.dimen.time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);

            mTimePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mHighTempPaint.setTextSize(tempTextSize);
            mLowTempPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            float centerX = bounds.centerX();
            mTime.setToNow();
            String timeText;
            if (isInAmbientMode()) {
                timeText = String.format("%02d:%02d", mTime.hour, mTime.minute);
            } else {
                timeText = String.format("%02d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            }
            float timeTextSize = mTimePaint.measureText(timeText);

            canvas.drawText(timeText, centerX - timeTextSize / 2, mYOffset, mTimePaint);
            String dateText = String.format("%s, %s %d %d", mDayNames[mTime.weekDay], mMonthNames[mTime.month], mTime.monthDay, mTime.year);
            float dateTextSize = mDatePaint.measureText(dateText);
            float dateYOffset = mYOffset + getResources().getDimension(R.dimen.time_text_margin_bottom);
            canvas.drawText(dateText.toUpperCase(), centerX - dateTextSize / 2, dateYOffset, mDatePaint);

            if (mHighTemp != null && mLowTemp != null) {
                float tempYOffset = dateYOffset + getResources().getDimension(R.dimen.date_text_margin_bottom);
                if (mIcon != null && !mLowBitAmbient)
                    canvas.drawBitmap(mIcon, centerX - mIcon.getWidth() - mIcon.getWidth() / 4, tempYOffset - mIcon.getHeight()/ 2 , mIconPaint);
                canvas.drawText(mHighTemp, centerX, tempYOffset, mHighTempPaint);
                float highTempSize = mHighTempPaint.measureText(mHighTemp);
                float highTempRightMargin = getResources().getDimension(R.dimen.temp_text_margin_right);
                canvas.drawText(mLowTemp, centerX + highTempSize + highTempRightMargin, tempYOffset, mLowTempPaint);
            }
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }



        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/sunshine-temp-update") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        mHighTemp = dataMap.getString("high-temp");
                        mLowTemp = dataMap.getString("low-temp");
                        new GetBitmapForWeatherTask().execute(dataMap.getAsset("icon"));

                        invalidate();
                    }
                }
            }

        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null)
                return null;

            ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess())
                return null;
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null)
                return null;
            return BitmapFactory.decodeStream(assetInputStream);
        }



    }
}
