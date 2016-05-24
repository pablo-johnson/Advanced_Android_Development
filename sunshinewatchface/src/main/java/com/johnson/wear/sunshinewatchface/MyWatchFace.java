/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.johnson.wear.sunshinewatchface;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private boolean isRound;

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

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {
        private static final String SUNSHINE_PATH = "/sunshine_weather";
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Calendar mCalendar;

        private String lastMaxTemp;
        private String lastMinTemp;
        private Bitmap mWeatherIconBitmap;
        float mXOffset;
        float mYOffset;

        boolean mLowBitAmbient;
        private GoogleApiClient googleClient;
        private String[] mDayNames;
        private String[] mMonthNames;
        private Time mTime;
        private Paint backgroundPaint;
        private Paint datePaint;
        private Paint maxPaint;
        private Paint minPaint;
        private Paint secondsPaint;
        private Paint timePaint;


        private float defaultOffset;
        private float timeYOffset = -1;
        private float dateYOffset;
        private float separatorYOffset;
        private float weatherYOffset;
        private Bitmap mWeatherIconGrayBitmap;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            googleClient = new GoogleApiClient.Builder(MyWatchFace.this).addApi(Wearable.API).addConnectionCallbacks(this).addOnConnectionFailedListener(this).build();
            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            
            mTime = new Time();

            //Get day and month names
            DateFormatSymbols symbols = new DateFormatSymbols();
            mDayNames = symbols.getShortWeekdays();
            mMonthNames = symbols.getShortMonths();
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
                connectGoogleApiClient();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                releaseGoogleApiClient();
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void connectGoogleApiClient() {
            if (googleClient != null && !googleClient.isConnected()) {
                googleClient.connect();
            }
        }

        private void releaseGoogleApiClient() {
            if (googleClient != null && googleClient.isConnected()) {
                Wearable.DataApi.removeListener(googleClient, this);
                googleClient.disconnect();
            }
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
            isRound = insets.isRound();
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
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();
            initValues();
            paintBackground(canvas, bounds);
            paintDateTime(canvas, bounds);
            paintWeather(canvas, bounds);

            paintExtras(canvas, bounds);
        }

        /**
         * Create the tools and values to be used to
         * locate and draw the information in the watch face
         */
        @TargetApi(Build.VERSION_CODES.M)
        private void initValues() {
            if (timeYOffset >= 0) {
                return;
            }

            Resources resources = getResources();

            backgroundPaint = new Paint();
            backgroundPaint.setColor(resources.getColor(R.color.skyBackground, getTheme()));

            defaultOffset = resources.getDimension(R.dimen.defaultTopMargin);
            int whiteColor = resources.getColor(R.color.digital_text, getTheme());
            int grayColor = resources.getColor(R.color.grayText, getTheme());

            float textSizeTime = resources.getDimension(R.dimen.timeTextSize);
            timePaint = createTextPaint(whiteColor, textSizeTime);

            int marginTop = isRound ? R.dimen.timeTopMarginRound : R.dimen.timeTopMargin;
            timeYOffset = resources.getDimension(marginTop) + textSizeTime;

            float textSizeSeconds = resources.getDimension(R.dimen.secondsTextSize);
            secondsPaint = createTextPaint(grayColor, textSizeSeconds);


            float textSizeDate = resources.getDimension(R.dimen.dateTextSize);
            datePaint = createTextPaint(whiteColor, textSizeDate);
            dateYOffset = defaultOffset + timeYOffset + textSizeDate;

            separatorYOffset = defaultOffset + dateYOffset;

            float textSizeWeather = resources.getDimension(R.dimen.weatherTextSize);
            maxPaint = createTextPaint(whiteColor, textSizeWeather);
            minPaint = createTextPaint(grayColor, textSizeWeather);
            weatherYOffset = defaultOffset + separatorYOffset + textSizeWeather;
        }

        private Paint createTextPaint(int textColor, float textSize) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextSize(textSize);
            return paint;
        }

        /**
         * Draw background from the watch face.
         * This can be updated to draw an image or animation if needed later on
         */
        private void paintBackground(Canvas canvas, Rect bounds) {
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);
            }
        }

        /**
         * Draw the information from the date and time into the watch face
         */
        private void paintDateTime(Canvas canvas, Rect bounds) {
            float centerX = bounds.centerX();

            String timeGeneral = String.format("%02d:%02d", mTime.hour, mTime.minute);

            float timeXOffset = timePaint.measureText(timeGeneral) / 2;
            canvas.drawText(timeGeneral, centerX - timeXOffset, timeYOffset, timePaint);

            if (!isInAmbientMode()) {
                String timeSeconds = String.format(":%02d", mTime.second);
                canvas.drawText(timeSeconds, centerX + timeXOffset, timeYOffset, secondsPaint);
            }

            String date = String.format("%s, %s %02d %04d", mDayNames[mTime.weekDay + 1].toUpperCase(), mMonthNames[mTime.month].toUpperCase(), mTime.monthDay, mTime.year);
            float dateXOffset = datePaint.measureText(date) / 2;
            canvas.drawText(date, centerX - dateXOffset, dateYOffset, datePaint);
        }

        /**
         * Draw the information from the weather into the watch face
         */
        private void paintWeather(Canvas canvas, Rect bounds) {
            float centerX = bounds.centerX();

            float maxXOffset = 0;
            if (lastMaxTemp != null) {
                maxXOffset = timePaint.measureText(lastMaxTemp) / 2;
                canvas.drawText(lastMaxTemp, centerX - maxXOffset, weatherYOffset, maxPaint);
            }

            if (lastMinTemp != null) {
                canvas.drawText(lastMinTemp, centerX + maxXOffset + defaultOffset, weatherYOffset, minPaint);
            }

            if (!isInAmbientMode()) {
                if (mWeatherIconBitmap != null) {
                    float iconXOffset = centerX - (defaultOffset + maxXOffset + mWeatherIconBitmap.getWidth());
                    float iconYOffset = weatherYOffset - (defaultOffset + mWeatherIconBitmap.getHeight()) / 2;
                    canvas.drawBitmap(mWeatherIconBitmap, iconXOffset, iconYOffset, null);
                }
            } else {
                if (mWeatherIconGrayBitmap != null) {
                    float iconXOffset = centerX - (defaultOffset + maxXOffset + mWeatherIconGrayBitmap.getWidth());
                    float iconYOffset = weatherYOffset - (defaultOffset + mWeatherIconGrayBitmap.getHeight()) / 2;
                    canvas.drawBitmap(mWeatherIconGrayBitmap, iconXOffset, iconYOffset, null);
                }
            }
        }

        /**
         * Draw additional content that is not part of the information displayed
         */
        private void paintExtras(Canvas canvas, Rect bounds) {
            canvas.drawRect(bounds.centerX() - defaultOffset,
                    separatorYOffset, bounds.centerX() + defaultOffset,
                    separatorYOffset + 1,
                    secondsPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
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
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(googleClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            /*IGNORE*/
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    String path = event.getDataItem().getUri().getPath();
                    if (path.equals(SUNSHINE_PATH)) {
                        lastMaxTemp = dataMap.getString("maxTemp");
                        lastMinTemp = dataMap.getString("minTemp");
                        int weatherId = dataMap.getInt("weatherId");

                        int resId = ArtUtility.getArtResourceForWeatherCondition(weatherId);
                        if (resId >= 0) {
                            mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(), resId);
                            int size = Double.valueOf(getResources().getDimension(R.dimen.weatherIconSize)).intValue();
                            mWeatherIconBitmap = Bitmap.createScaledBitmap(mWeatherIconBitmap, size, size, false);
                            initGrayBackgroundBitmap();
                        }
                        invalidate();
                    }
                }
            }
        }

        /**
         * Generate gray bitmap from the current weather icon
         */
        private void initGrayBackgroundBitmap() {
            mWeatherIconGrayBitmap = Bitmap.createBitmap(
                    mWeatherIconBitmap.getWidth(),
                    mWeatherIconBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mWeatherIconGrayBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mWeatherIconBitmap, 0, 0, grayPaint);
        }
    }
}
